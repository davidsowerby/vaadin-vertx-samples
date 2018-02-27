package com.github.mcollovati.vertx.vaadin;

import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.mcollovati.vertx.vaadin.communication.SockJSPushHandler;
import com.github.mcollovati.vertx.web.ExtendedSession;
import com.github.mcollovati.vertx.web.sstore.ExtendedLocalSessionStore;
import com.github.mcollovati.vertx.web.sstore.ExtendedSessionStore;
import com.github.mcollovati.vertx.web.sstore.NearCacheSessionStore;
import com.vaadin.server.DefaultDeploymentConfiguration;
import com.vaadin.server.ServiceException;
import com.vaadin.server.WrappedSession;
import com.vaadin.shared.Registration;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.VertxException;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.eventbus.MessageProducer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.Session;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CookieHandler;
import io.vertx.ext.web.handler.SessionHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;
import io.vertx.ext.web.handler.sockjs.SockJSHandlerOptions;

import static io.vertx.ext.web.handler.SessionHandler.DEFAULT_SESSION_TIMEOUT;

public class VertxVaadin {

    private static final String VAADIN_SESSION_EXPIRED_ADDRESS = "vaadin.session.expired";

    private final VertxVaadinService service;
    private final JsonObject config;
    private final Vertx vertx;
    private final Router router;
    private final ExtendedSessionStore sessionStore;


    private VertxVaadin(Vertx vertx, Optional<ExtendedSessionStore> sessionStore, JsonObject config) {
        this.vertx = Objects.requireNonNull(vertx);
        this.config = Objects.requireNonNull(config);
        this.service = createVaadinService();
        try {
            service.init();
        } catch (Exception ex) {
            throw new VertxException("Cannot initialize Vaadin service", ex);
        }

        //SessionStore adaptedSessionStore = SessionStoreAdapter.adapt(service, sessionStore.orElseGet(this::createSessionStore));
        this.sessionStore = withSessionExpirationHandler(
            this.service, sessionStore.orElseGet(this::createSessionStore)
        );
        configureSessionStore();
        this.router = initRouter();
    }

    protected VertxVaadin(Vertx vertx, ExtendedSessionStore sessionStore, JsonObject config) {
        this(vertx, Optional.of(sessionStore), config);
    }

    protected VertxVaadin(Vertx vertx, JsonObject config) {
        this(vertx, Optional.empty(), config);
    }

    private void configureSessionStore() {
        Registration sessionInitListenerReg = this.service.addSessionInitListener(event -> {
            MessageConsumer<String> consumer = sessionExpiredHandler(vertx, msg ->
                Optional.of(event.getSession().getSession())
                    .filter(session -> msg.body().equals(session.getId()))
                    .ifPresent(WrappedSession::invalidate));
            event.getService().addSessionDestroyListener(ev2 -> consumer.unregister());

        });
        this.service.addServiceDestroyListener(event -> sessionInitListenerReg.remove());
    }

    public Router router() {
        return router;
    }

    public final Vertx vertx() {
        return vertx;
    }

    public final VertxVaadinService vaadinService() {
        return service;
    }

    public String serviceName() {
        return config.getString("serviceName", getClass().getName() + ".service");
    }


    public void runWithSession(String sessionId, Handler<AsyncResult<ExtendedSession>> handler) {
        sessionStore.get(sessionId, res -> {
            if (res.succeeded() && res.result() != null) {
                runAndCommitSessionChanges(res.result(), handler)
                    .handle(Future.succeededFuture(ExtendedSession.adapt(res.result())));
            } else {
                handler.handle(Future.failedFuture("Session does not exists: " + sessionId));
            }
        });
    }

    public final <T> Handler<T> runAndCommitSessionChanges(Session session, Handler<T> handler) {
        return obj -> {
            try {
                handler.handle(obj);
            } finally {
                if (!session.isDestroyed()) {
                    session.setAccessed();
                    sessionStore.put(session, res -> {
                        if (res.failed()) {
                            getLogger().log(Level.SEVERE, "Failed to store session", res.cause());
                        }
                    });
                } else {
                    sessionStore.delete(session.id(), res -> {
                        if (res.failed()) {
                            getLogger().log(Level.SEVERE, "Failed to delete session", res.cause());
                        }
                    });
                }
            }
        };
    }


    protected final JsonObject config() {
        return config;
    }

    protected void serviceInitialized(Router router) {
    }

    protected VertxVaadinService createVaadinService() {
        return new VertxVaadinService(this, createDeploymentConfiguration());
    }

    protected ExtendedSessionStore createSessionStore() {
        if (vertx.isClustered()) {
            return NearCacheSessionStore.create(vertx);
        }
        return ExtendedLocalSessionStore.create(vertx);
    }

    private Router initRouter() {

        String sessionCookieName = sessionCookieName();
        SessionHandler sessionHandler = SessionHandler.create(sessionStore)
            .setSessionTimeout(config().getLong("sessionTimeout", DEFAULT_SESSION_TIMEOUT))
            .setSessionCookieName(sessionCookieName)
            .setNagHttps(false)
            .setCookieHttpOnlyFlag(true);

        Router vaadinRouter = Router.router(vertx);
        vaadinRouter.route().handler(CookieHandler.create());
        vaadinRouter.route().handler(BodyHandler.create());
        // Disable SessionHandler for /VAADIN/ static resources
        vaadinRouter.routeWithRegex("^(?!/VAADIN/).*$").handler(sessionHandler);

        // Forward vaadinPush javascript to sockjs implementation
        vaadinRouter.routeWithRegex("/VAADIN/vaadinPush(\\.debug)?\\.js")
            .handler(ctx -> ctx.reroute("/VAADIN/vaadinPushSockJS.js"));

        vaadinRouter.route("/VAADIN/*").handler(StaticHandler.create("VAADIN", getClass().getClassLoader()));

        initSockJS(vaadinRouter, sessionHandler);

        vaadinRouter.route("/*").handler(routingContext -> {
            VertxVaadinRequest request = new VertxVaadinRequest(service, routingContext);
            VertxVaadinResponse response = new VertxVaadinResponse(service, routingContext);

            try {
                service.handleRequest(request, response);
                response.end();
            } catch (ServiceException ex) {
                routingContext.fail(ex);
            }
        });


        serviceInitialized(vaadinRouter);
        return vaadinRouter;
    }

    private void initSockJS(Router vaadinRouter, SessionHandler sessionHandler) {

        SockJSHandlerOptions options = new SockJSHandlerOptions()
            .setSessionTimeout(config().getLong("sessionTimeout", DEFAULT_SESSION_TIMEOUT))
            .setHeartbeatInterval(service.getDeploymentConfiguration().getHeartbeatInterval() * 1000);
        SockJSHandler sockJSHandler = SockJSHandler.create(vertx, options);
        SockJSPushHandler pushHandler = new SockJSPushHandler(service, sessionHandler, sockJSHandler);
        vaadinRouter.route("/PUSH/*").handler(pushHandler);
    }


    private String sessionCookieName() {
        return config().getString("sessionCookieName", "vertx-web.session");
    }

    private DefaultDeploymentConfiguration createDeploymentConfiguration() {
        return new DefaultDeploymentConfiguration(getClass(), initProperties());
    }

    private Properties initProperties() {
        Properties initParameters = new Properties();
        initParameters.putAll(config().getMap());
        return initParameters;
    }

    // TODO: change JsonObject to VaadinOptions interface
    public static VertxVaadin create(Vertx vertx, ExtendedSessionStore sessionStore, JsonObject config) {
        return new VertxVaadin(vertx, sessionStore, config);
    }

    public static VertxVaadin create(Vertx vertx, JsonObject config) {
        return new VertxVaadin(vertx, config);
    }

    private static final Logger getLogger() {
        return Logger.getLogger(VertxVaadin.class.getName());
    }


    private static ExtendedSessionStore withSessionExpirationHandler(
        VertxVaadinService service, ExtendedSessionStore store
    ) {
        MessageProducer<String> sessionExpiredProducer = sessionExpiredProducer(service);
        store.expirationHandler(res -> {
            if (res.succeeded()) {
                sessionExpiredProducer.send(res.result());
            } else {
                res.cause().printStackTrace();
            }
        });
        return store;
    }

    private static MessageProducer<String> sessionExpiredProducer(VertxVaadinService vaadinService) {
        return vaadinService.getVertx().eventBus().sender(VAADIN_SESSION_EXPIRED_ADDRESS);
    }

    public static MessageConsumer<String> sessionExpiredHandler(Vertx vertx, Handler<Message<String>> handler) {
        return vertx.eventBus().consumer(VAADIN_SESSION_EXPIRED_ADDRESS, handler);
    }

}
