package com.github.mcollovati.vertx.vaadin.communication;

import java.io.IOException;
import java.io.Reader;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.mcollovati.vertx.vaadin.VertxVaadinRequest;
import com.github.mcollovati.vertx.vaadin.VertxVaadinService;
import com.vaadin.server.ErrorEvent;
import com.vaadin.server.ErrorHandler;
import com.vaadin.server.LegacyCommunicationManager;
import com.vaadin.server.ServiceException;
import com.vaadin.server.ServletPortletHelper;
import com.vaadin.server.SessionExpiredException;
import com.vaadin.server.SystemMessages;
import com.vaadin.server.VaadinRequest;
import com.vaadin.server.VaadinService;
import com.vaadin.server.VaadinSession;
import com.vaadin.server.communication.ExposeVaadinCommunicationPkg;
import com.vaadin.server.communication.PushConnection;
import com.vaadin.server.communication.ServerRpcHandler;
import com.vaadin.shared.ApplicationConstants;
import com.vaadin.shared.communication.PushMode;
import com.vaadin.ui.UI;
import com.vaadin.util.CurrentInstance;
import elemental.json.JsonException;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.Session;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;
import io.vertx.ext.web.handler.sockjs.SockJSSocket;
import io.vertx.ext.web.sstore.SessionStore;

/**
 * Handles incoming push connections and messages and dispatches them to the
 * correct {@link UI}/ {@link SockJSPushConnection}.
 *
 * Source code adapted from Vaadin {@link com.vaadin.server.communication.PushHandler}
 */
public class SockJSPushHandler implements Handler<RoutingContext> {


    /**
     * Callback used when we receive a UIDL request through Atmosphere. If the
     * push channel is bidirectional (websockets), the request was sent via the
     * same channel. Otherwise, the client used a separate AJAX request. Handle
     * the request and send changed UI state via the push channel (we do not
     * respond to the request directly.)
     */
    private final PushEventCallback receiveCallback = (PushEvent event, UI ui) -> {

        getLogger().log(Level.FINER, "Received message from resource {0}", event.socket().getUUID());

        PushSocket socket = event.socket;
        SockJSPushConnection connection = getConnectionForUI(ui);

        assert connection != null : "Got push from the client "
            + "even though the connection does not seem to be "
            + "valid. This might happen if a HttpSession is "
            + "serialized and deserialized while the push "
            + "connection is kept open or if the UI has a "
            + "connection of unexpected type.";


        Reader reader = event.message().map(connection::receiveMessage).orElse(null);
        if (reader == null) {
            // The whole message was not yet received
            return;
        }


        // Should be set up by caller
        VaadinRequest vaadinRequest = VaadinService.getCurrentRequest();
        assert vaadinRequest != null;

        try {
            new ServerRpcHandler().handleRpc(ui, reader, vaadinRequest);
            connection.push(false);
        } catch (JsonException e) {
            getLogger().log(Level.SEVERE, "Error writing JSON to response", e);
            // Refresh on client side
            sendRefreshAndDisconnect(socket);
        } catch (LegacyCommunicationManager.InvalidUIDLSecurityKeyException e) {
            getLogger().log(Level.WARNING,
                "Invalid security key received from {0}",
                socket.remoteAddress());
            // Refresh on client side
            sendRefreshAndDisconnect(socket);
        }
    };
    private final VertxVaadinService service;
    private final SockJSHandler sockJSHandler;
    private final SessionStore sessionStore;
    private final Map<String, SockJSSocket> connectedSockets;
    private final String socketHandlerAddress;
    private final MessageConsumer<JsonObject> registration;
    /**
     * Callback used when we receive a request to establish a push channel for a
     * UI. Associate the SockJS socket with the UI and leave the connection
     * open. If there is a pending push, send it now.
     */
    private final PushEventCallback establishCallback = (PushEvent event, UI ui) -> {
        getLogger().log(Level.FINER,
            "New push connection for resource {0} with transport {1}",
            new Object[]{event.socket().getUUID(), "resource.transport()"});


        // TODO: verify if this is needed with non websocket transports
        //event.routingContext.response().putHeader("Content-Type", "text/plain; charset=UTF-8");
        //resource.getResponse().setContentType("text/plain; charset=UTF-8");

        VaadinSession session = ui.getSession();
        PushSocket socket = event.socket;

        HttpServerRequest request = event.routingContext.request();

        String requestToken = request.getParam(ApplicationConstants.PUSH_ID_PARAMETER);
        if (!isPushIdValid(session, requestToken)) {
            getLogger().log(Level.WARNING,
                "Invalid identifier in new connection received from {0}",
                socket.remoteAddress());
            // Refresh on client side, create connection just for
            // sending a message
            sendRefreshAndDisconnect(socket);
            return;
        }

        SockJSPushConnection connection = getConnectionForUI(ui);
        assert (connection != null);

        connection.connect(socket);
        //connection.connect(socket);
    };

    public SockJSPushHandler(VertxVaadinService service, SessionStore sessionStore, SockJSHandler sockJSHandler) {
        this.service = service;
        this.sessionStore = sessionStore;
        this.sockJSHandler = sockJSHandler;
        this.socketHandlerAddress = UUID.randomUUID().toString();
        this.connectedSockets = new ConcurrentHashMap<>();

        this.registration = service.getVertx().eventBus().<JsonObject>localConsumer(socketHandlerAddress, this::handlePushAction);
        service.addServiceDestroyListener(event -> this.registration.unregister());

        this.sockJSHandler.socketHandler(this::onConnect);

    }

    private void handlePushAction(Message<JsonObject> message) {
        String socketID = message.headers().get("socketUUID");
        SockJSSocket socket = connectedSockets.get(socketID);
        if (socket != null) {
            JsonObject payload = message.body();
            PushSocket.Action.fromJsonObject(payload)
                .onAction(socket, payload);
            message.reply(null);
        } else {
            message.fail(404, "SockJSSocket not registered: " + socketID);
        }
    }

    private void onConnect(SockJSSocket sockJSSocket) {
        RoutingContext routingContext = CurrentInstance.get(RoutingContext.class);

        String uuid = sockJSSocket.writeHandlerID();
        connectedSockets.put(uuid, sockJSSocket);
        PushSocket socket = new PushSocketImpl(sockJSSocket, socketHandlerAddress);

        // Send an ACK
        socket.send("ACK-CONN|" + uuid);

        runAndCommitSessionChanges(sockJSSocket.webSession(), unused -> {
            callWithUi(new PushEvent(socket, routingContext, null), establishCallback);
        }).handle(null);

        sockJSSocket.handler(
            runAndCommitSessionChanges(
                sockJSSocket.webSession(), data -> onMessage(new PushEvent(socket, routingContext, data))
            ));
        sockJSSocket.endHandler(runAndCommitSessionChanges(
            sockJSSocket.webSession(), x -> onDisconnect(new PushEvent(socket, routingContext, null))
        ));
        sockJSSocket.exceptionHandler(
            runAndCommitSessionChanges(
                sockJSSocket.webSession(), t -> onError(new PushEvent(socket, routingContext, null), t)
            ));
    }

    private <T> Handler<T> runAndCommitSessionChanges(Session session, Handler<T> handler) {
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

    private void onDisconnect(PushEvent ev) {
        connectedSockets.remove(ev.socket().getUUID());
        connectionLost(ev);
    }

    private void onError(PushEvent ev, Throwable t) {
        getLogger().log(Level.SEVERE, "Exception in push connection", t);
        connectionLost(ev);
    }

    private void onMessage(PushEvent event) {
        callWithUi(event, receiveCallback);
    }

    @Override
    public void handle(RoutingContext routingContext) {
        CurrentInstance.set(RoutingContext.class, routingContext);
        try {
            sockJSHandler.handle(routingContext);
        } finally {
            CurrentInstance.set(RoutingContext.class, null);
        }
    }

    private void callWithUi(final PushEvent event, final PushEventCallback callback) {

        PushSocket socket = event.socket;
        RoutingContext routingContext = event.routingContext;
        VertxVaadinRequest vaadinRequest = new VertxVaadinRequest(service, routingContext);
        VaadinSession session = null;

        service.requestStart(vaadinRequest, null);
        try {
            try {
                session = service.findVaadinSession(vaadinRequest);
                assert VaadinSession.getCurrent() == session;

            } catch (ServiceException e) {
                getLogger().log(Level.SEVERE,
                    "Could not get session. This should never happen", e);
                return;
            } catch (SessionExpiredException e) {
                SystemMessages msg = service
                    .getSystemMessages(ServletPortletHelper.findLocale(null,
                        null, vaadinRequest), vaadinRequest);
                sendNotificationAndDisconnect(socket,
                    VaadinService.createCriticalNotificationJSON(
                        msg.getSessionExpiredCaption(),
                        msg.getSessionExpiredMessage(), null,
                        msg.getSessionExpiredURL()));
                return;
            }

            UI ui = null;
            session.lock();
            try {
                ui = service.findUI(vaadinRequest);
                assert UI.getCurrent() == ui;

                if (ui == null) {
                    sendNotificationAndDisconnect(
                        socket, ExposeVaadinCommunicationPkg.getUINotFoundErrorJSON(service, vaadinRequest)
                    );
                } else {
                    callback.run(event, ui);
                }
            } catch (final IOException e) {
                callErrorHandler(session, e);
            } catch (final Exception e) {
                SystemMessages msg = service
                    .getSystemMessages(ServletPortletHelper.findLocale(null,
                        null, vaadinRequest), vaadinRequest);


                /* TODO: verify */
                PushSocket errorSocket = getOpenedPushConnection(socket, ui);
                sendNotificationAndDisconnect(errorSocket,
                    VaadinService.createCriticalNotificationJSON(
                        msg.getInternalErrorCaption(),
                        msg.getInternalErrorMessage(), null,
                        msg.getInternalErrorURL()));
                callErrorHandler(session, e);
            } finally {
                try {
                    session.unlock();
                } catch (Exception e) {
                    getLogger().log(Level.WARNING,
                        "Error while unlocking session", e);
                    // can't call ErrorHandler, we (hopefully) don't have a lock
                }
            }
        } finally {
            try {
                service.requestEnd(vaadinRequest, null, session);
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Error while ending request", e);

                // can't call ErrorHandler, we don't have a lock
            }

        }
    }

    private PushSocket getOpenedPushConnection(PushSocket socket, UI ui) {
        PushSocket errorSocket = socket;
        if (ui != null && ui.getPushConnection() != null) {
            // We MUST use the opened push connection if there is one.
            // Otherwise we will write the response to the wrong request
            // when using streaming (the client -> server request
            // instead of the opened push channel)
            errorSocket = ((SockJSPushConnection) ui.getPushConnection()).getSocket();
        }
        return errorSocket;
    }

    /**
     * Call the session's {@link ErrorHandler}, if it has one, with the given
     * exception wrapped in an {@link ErrorEvent}.
     */
    private void callErrorHandler(VaadinSession session, Exception e) {
        try {
            ErrorHandler errorHandler = ErrorEvent.findErrorHandler(session);
            if (errorHandler != null) {
                errorHandler.error(new ErrorEvent(e));
            }
        } catch (Exception ex) {
            // Let's not allow error handling to cause trouble; log fails
            getLogger().log(Level.WARNING, "ErrorHandler call failed", ex);
        }
    }

    void connectionLost(PushEvent event) {
        // We don't want to use callWithUi here, as it assumes there's a client
        // request active and does requestStart and requestEnd among other
        // things.

        VaadinRequest vaadinRequest = new VertxVaadinRequest(service, event.routingContext);
        VaadinSession session = null;

        try {
            session = service.findVaadinSession(vaadinRequest);
        } catch (ServiceException e) {
            getLogger().log(Level.SEVERE,
                "Could not get session. This should never happen", e);
            return;
        } catch (SessionExpiredException e) {
            // This happens at least if the server is restarted without
            // preserving the session. After restart the client reconnects, gets
            // a session expired notification and then closes the connection and
            // ends up here
            getLogger().log(Level.FINER,
                "Session expired before push disconnect event was received",
                e);
            return;
        }

        UI ui = null;
        session.lock();
        try {
            VaadinSession.setCurrent(session);
            // Sets UI.currentInstance
            ui = service.findUI(vaadinRequest);
            if (ui == null) {
                /*
                 * UI not found, could be because FF has asynchronously closed
                 * the websocket connection and Atmosphere has already done
                 * cleanup of the request attributes.
                 *
                 * In that case, we still have a chance of finding the right UI
                 * by iterating through the UIs in the session looking for one
                 * using the same AtmosphereResource.
                 */
                ui = findUiUsingSocket(event.socket(), session.getUIs());

                if (ui == null) {
                    getLogger().log(Level.FINE,
                        "Could not get UI. This should never happen,"
                            + " except when reloading in Firefox and Chrome -"
                            + " see http://dev.vaadin.com/ticket/14251.");
                    return;
                } else {
                    getLogger().log(Level.INFO,
                        "No UI was found based on data in the request,"
                            + " but a slower lookup based on the AtmosphereResource succeeded."
                            + " See http://dev.vaadin.com/ticket/14251 for more details.");
                }
            }

            PushMode pushMode = ui.getPushConfiguration().getPushMode();
            SockJSPushConnection pushConnection = getConnectionForUI(ui);

            String id = event.socket().getUUID();

            if (pushConnection == null) {
                getLogger().log(Level.WARNING,
                    "Could not find push connection to close: {0} with transport {1}",
                    new Object[]{id, "resource.transport()"});
            } else {
                if (!pushMode.isEnabled()) {
                    /*
                     * The client is expected to close the connection after push
                     * mode has been set to disabled.
                     */
                    getLogger().log(Level.FINER,
                        "Connection closed for resource {0}", id);
                } else {
                    /*
                     * Unexpected cancel, e.g. if the user closes the browser
                     * tab.
                     */
                    getLogger().log(Level.FINER,
                        "Connection unexpectedly closed for resource {0} with transport {1}",
                        new Object[]{id, "resource.transport()"});
                }

                pushConnection.connectionLost();
            }

        } catch (final Exception e) {
            callErrorHandler(session, e);
        } finally {
            try {
                session.unlock();
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Error while unlocking session",
                    e);
                // can't call ErrorHandler, we (hopefully) don't have a lock
            }
        }
    }

    /**
     * Checks whether a given push id matches the session's push id.
     *
     * @param session       the vaadin session for which the check should be done
     * @param requestPushId the push id provided in the request
     * @return {@code true} if the id is valid, {@code false} otherwise
     */
    private static boolean isPushIdValid(VaadinSession session, String requestPushId) {

        String sessionPushId = session.getPushId();
        return requestPushId != null && requestPushId.equals(sessionPushId);
    }

    /**
     * Tries to send a critical notification to the client and close the
     * connection. Does nothing if the connection is already closed.
     */
    private static void sendNotificationAndDisconnect(
        PushSocket socket, String notificationJson) {
        try {
            socket.send(notificationJson);
            socket.close();
        } catch (Exception e) {
            getLogger().log(Level.FINEST,
                "Failed to send critical notification to client", e);
        }
    }

    private static final Logger getLogger() {
        return Logger.getLogger(SockJSPushHandler.class.getName());
    }

    private static SockJSPushConnection getConnectionForUI(UI ui) {
        PushConnection pushConnection = ui.getPushConnection();
        if (pushConnection instanceof SockJSPushConnection) {
            return (SockJSPushConnection) pushConnection;
        } else {
            return null;
        }
    }

    private static UI findUiUsingSocket(PushSocket socket, Collection<UI> uIs) {
        for (UI ui : uIs) {
            PushConnection pushConnection = ui.getPushConnection();
            if (pushConnection instanceof SockJSPushConnection) {
                if (((SockJSPushConnection) pushConnection)
                    .getSocket() == socket) {
                    return ui;
                }
            }
        }
        return null;
    }

    private static void sendRefreshAndDisconnect(PushSocket socket) {
        sendNotificationAndDisconnect(socket, VaadinService
            .createCriticalNotificationJSON(null, null, null, null));
    }

    private interface PushEventCallback {
        void run(PushEvent event, UI ui) throws IOException;
    }

    private static class PushSocketImpl implements PushSocket {

        private final String socketUUID;
        private final String socketHandlerAddress;
        private final String remoteAddress;

        public PushSocketImpl(SockJSSocket socket, String socketHandlerAddress) {
            this.socketUUID = socket.writeHandlerID();
            this.remoteAddress = socket.remoteAddress().toString();
            this.socketHandlerAddress = socketHandlerAddress;
        }

        @Override
        public String getUUID() {
            return socketUUID;
        }

        @Override
        public String remoteAddress() {
            return remoteAddress;
        }

        @Override
        public CompletionStage<?> send(String message) {
            JsonObject payload = Action.SEND.toJsonObject()
                .put("message", message);
            return runCommand(payload);
        }

        @Override
        public CompletionStage<?> close() {

            return runCommand(Action.CLOSE.toJsonObject());
        }

        private CompletionStage<?> runCommand(JsonObject payload) {
            CompletableFuture<?> completer = new CompletableFuture<>();
            DeliveryOptions opts = new DeliveryOptions();
            opts.addHeader("socketUUID", socketUUID);
            Vertx.currentContext().owner().eventBus()
                .send(socketHandlerAddress, payload, opts, res -> completer.complete(null));
            return completer;
        }
    }

    private static class PushEvent {
        private final Buffer message;
        private final RoutingContext routingContext;
        private final PushSocket socket;

        PushEvent(PushSocket socket, RoutingContext routingContext, Buffer message) {
            this.message = message;
            this.routingContext = routingContext;
            this.socket = socket;
        }

        public PushSocket socket() {
            return socket;
        }

        Optional<Buffer> message() {
            return Optional.ofNullable(message);
        }
    }


}
