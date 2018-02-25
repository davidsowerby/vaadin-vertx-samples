package com.github.mcollovati.vertx.web.sstore;

import io.vertx.core.Vertx;
import io.vertx.ext.web.sstore.SessionStore;

public interface NearCacheSessionStore extends SessionStore {
    /**
     * The default name used for the session map
     */
    String DEFAULT_SESSION_MAP_NAME = "vertx-web.sessions";

    /**
     * Default retry time out, in ms, for a session not found in this store.
     */
    long DEFAULT_RETRY_TIMEOUT = 5 * 1000; // 5 seconds

    /**
     * Create a session store
     *
     * @param vertx  the Vert.x instance
     * @param sessionMapName  the session map name
     * @return the session store
     */
    static NearCacheSessionStore create(Vertx vertx, String sessionMapName) {
        return new NearCacheSessionStoreImpl(vertx, sessionMapName, DEFAULT_RETRY_TIMEOUT);
    }

    /**
     * Create a session store.<p/>
     *
     * The retry timeout value, configures how long the session handler will retry to get a session from the store
     * when it is not found.
     *
     * @param vertx  the Vert.x instance
     * @param sessionMapName  the session map name
     * @param retryTimeout the store retry timeout, in ms
     * @return the session store
     */
    static NearCacheSessionStore create(Vertx vertx, String sessionMapName, long retryTimeout) {
        return new NearCacheSessionStoreImpl(vertx, sessionMapName, retryTimeout);
    }

    /**
     * Create a session store
     *
     * @param vertx  the Vert.x instance
     * @return the session store
     */
    static NearCacheSessionStore create(Vertx vertx) {
        return new NearCacheSessionStoreImpl(vertx, DEFAULT_SESSION_MAP_NAME, DEFAULT_RETRY_TIMEOUT);
    }

    /**
     * Create a session store.<p/>
     *
     * The retry timeout value, configures how long the session handler will retry to get a session from the store
     * when it is not found.
     *
     * @param vertx  the Vert.x instance
     * @param retryTimeout the store retry timeout, in ms
     * @return the session store
     */
    static NearCacheSessionStore create(Vertx vertx, long retryTimeout) {
        return new NearCacheSessionStoreImpl(vertx, DEFAULT_SESSION_MAP_NAME, retryTimeout);
    }

}
