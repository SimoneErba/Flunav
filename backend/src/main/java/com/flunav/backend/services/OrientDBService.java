package com.flunav.backend.services;

import com.flunav.backend.context.DatabaseContextHolder;
import com.orientechnologies.orient.core.db.ODatabasePool;
import com.orientechnologies.orient.core.db.ODatabaseSession;
import com.orientechnologies.orient.core.db.ODatabaseType;
import com.orientechnologies.orient.core.db.OrientDB;
import com.orientechnologies.orient.core.db.OrientDBConfig;
import com.orientechnologies.orient.core.metadata.schema.OClass;
import com.orientechnologies.orient.core.metadata.schema.OType;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap; // CHANGED

@Service
public class OrientDBService {
    private static final Logger logger = LoggerFactory.getLogger(OrientDBService.class);

    private OrientDB orientDB;
    // CHANGED: From a single pool to a map of pools
    private final ConcurrentMap<String, ODatabasePool> databasePools = new ConcurrentHashMap<>();

    @Value("${orientdb.url}")
    private String dbUrl;
    @Value("${orientdb.db.name}")
    private String mainDbName;
    @Value("${orientdb.username}")
    private String username;
    @Value("${orientdb.password}")
    private String password;

    private final Set<String> activeSimulations = ConcurrentHashMap.newKeySet();

    private static final ThreadLocal<ODatabaseSession> transactionalSession = new ThreadLocal<>();

    @PostConstruct
    public void init() {
        orientDB = new OrientDB(dbUrl, username, password, OrientDBConfig.defaultConfig());

        if (!orientDB.exists(mainDbName)) {
            orientDB.create(mainDbName, ODatabaseType.PLOCAL);
        }
        // CHANGED: Create and store the main pool in the map
        databasePools.put(mainDbName, new ODatabasePool(orientDB, mainDbName, username, password));
        ensureSchemaExists(mainDbName);

        logger.info("OrientDB connection pool for main DB '{}' initialized.", mainDbName);
    }

    @PreDestroy
    public void close() {
        // CHANGED: Close all active pools
        if (databasePools != null) {
            databasePools.values().forEach(ODatabasePool::close);
            databasePools.clear();
        }
        if (orientDB != null) {
            activeSimulations.forEach(this::dropDatabase); // This will also close pools
            orientDB.close();
        }
        logger.info("OrientDB service has been shut down.");
    }

    public ODatabaseSession getSession() {
        ODatabaseSession activeSession = transactionalSession.get();

        // Transactional proxy logic remains the same
        if (activeSession != null && !activeSession.isClosed()) {
            logger.trace("Wrapping existing transactional session in a Proxy.");
            return (ODatabaseSession) Proxy.newProxyInstance(
                    OrientDBService.class.getClassLoader(),
                    new Class<?>[] { ODatabaseSession.class },
                    (proxy, method, args) -> {
                        if ("close".equals(method.getName())) {
                            logger.trace("Ignored close() call on transactional proxy.");
                            return null;
                        }
                        activeSession.activateOnCurrentThread();
                        return method.invoke(activeSession, args);
                    });
        }

        // --- REFACTORED LOGIC ---
        String simulationId = DatabaseContextHolder.getSimulationId();
        String dbName = (simulationId != null) ? simulationId : mainDbName;

        ODatabasePool pool = databasePools.get(dbName);
        if (pool == null) {
            // This is a safeguard. In normal operation, the pool should always exist if the
            // DB exists.
            throw new IllegalStateException(
                    "No database pool found for '" + dbName + "'. Was the database created correctly?");
        }

        logger.trace("Acquiring new session for DB '{}' from its pool.", dbName);
        var session = pool.acquire();
        session.activateOnCurrentThread();
        return session;
    }

    public ODatabaseSession getSession(String dbName) {
        // CHANGED: Use the pool map for direct access as well
        String targetDb = (dbName != null) ? dbName : mainDbName;
        ODatabasePool pool = databasePools.get(targetDb);
        if (pool == null) {
            throw new IllegalStateException("No database pool found for '" + targetDb + "'.");
        }
        return pool.acquire();
    }

    // withTransaction and withSession remain unchanged as they rely on getSession()
    public void withTransaction(TransactionalCallback callback) {
        try (ODatabaseSession session = getSession()) {
            transactionalSession.set(session);
            try {
                session.begin();
                callback.execute(session);
                session.activateOnCurrentThread();
                session.commit();
                logger.debug("Transaction committed successfully.");
            } catch (Exception e) {
                logger.error("Error during transactional callback. Initiating rollback.", e);
                if (!session.isClosed() && session.getTransaction().isActive()) {
                    try {
                        session.activateOnCurrentThread();
                        session.rollback();
                        logger.info("Transaction rolled back successfully.");
                    } catch (Exception rollbackEx) {
                        logger.error("Critical error: Failed to rollback transaction.", rollbackEx);
                        e.addSuppressed(rollbackEx);
                    }
                }
                throw new RuntimeException("Transactional callback failed, operation was rolled back.", e);
            }
        } finally {
            transactionalSession.remove();
        }
    }

    public void withSession(SessionCallback callback) {
        ODatabaseSession session = getSession();

        try {
            session.activateOnCurrentThread();
            callback.execute(session);
        } catch (Exception e) {
            logger.error("Error executing session callback on context-aware DB", e);
            throw new RuntimeException("Session callback failed", e);
        } finally {
            if (session != null) {
                try {
                    session.activateOnCurrentThread();
                    session.close();
                } catch (Exception closeEx) {
                    logger.warn("Ignored error while closing the OrientDB session: {}", closeEx.getMessage());
                }
            }
        }
    }

    @FunctionalInterface
    public interface TransactionalCallback {
        void execute(ODatabaseSession session);
    }

    @FunctionalInterface
    public interface SessionCallback {
        void execute(ODatabaseSession session);
    }

    public void createInMemoryDatabase(String dbName) {
        try {
            if (orientDB.exists(dbName)) {
                orientDB.drop(dbName);
            }
            orientDB.create(dbName, ODatabaseType.MEMORY);

            // NEW: Create and store a new pool for the simulation database
            ODatabasePool newPool = new ODatabasePool(orientDB, dbName, username, password);
            databasePools.put(dbName, newPool);

            activeSimulations.add(dbName);
            logger.info("Successfully created new in-memory simulation database and its pool: {}", dbName);
            try (var context = DatabaseContextHolder.enterSimulationContext(dbName)) {
                ensureSchemaExists(dbName);
            }

        } catch (Exception e) {
            logger.error("Failed to create in-memory simulation DB '{}'", dbName, e);
            // Cleanup in case of failure
            dropDatabase(dbName); // This will handle pool closure and removal
            throw new RuntimeException("Simulation DB creation failed.", e);
        }
    }

    public void dropDatabase(String dbName) {
        // NEW: Close and remove the pool associated with the database
        ODatabasePool pool = databasePools.remove(dbName);
        if (pool != null) {
            pool.close();
            logger.info("Closed and removed pool for database: {}", dbName);
        }

        if (orientDB.exists(dbName)) {
            orientDB.drop(dbName);
            activeSimulations.remove(dbName);
            logger.info("Dropped simulation database: {}", dbName);
        }
    }

    // ensureSchemaExists and createIndexes remain unchanged
    private void ensureSchemaExists(String dbName) {
        try (ODatabaseSession session = getSession(dbName)) {
            // 1. Location (Node/Waypoint)
            if (session.getClass("Location") == null) {
                OClass locationClass = session.createVertexClass("Location");
                locationClass.createProperty("customId", OType.STRING).setNotNull(true);
            }

            // 2. Item (Metadata)
            if (session.getClass("Item") == null) {
                OClass itemClass = session.createVertexClass("Item");
                itemClass.createProperty("customId", OType.STRING).setNotNull(true);
            }

            // 3. Conveyor (Edge) - REPLACES ConnectedTo
            if (session.getClass("Conveyor") == null) {
                OClass conveyorClass = session.createEdgeClass("Conveyor");
                conveyorClass.createProperty("customId", OType.STRING).setNotNull(true);
                conveyorClass.createProperty("length", OType.DOUBLE);
                conveyorClass.createProperty("speed", OType.DOUBLE);
            }

            // 4. DisplayRules (Document)
            if (session.getClass("DisplayRules") == null) {
                OClass displayRulesClass = session.createClass("DisplayRules");
                displayRulesClass.createProperty("rules", OType.EMBEDDEDLIST, OType.EMBEDDED);
            }

            // 5. User (Document)
            if (session.getClass("User") == null) {
                OClass userClass = session.createClass("User");
                userClass.createProperty("username", OType.STRING).setNotNull(true).setMandatory(true);
                userClass.createProperty("password", OType.STRING).setNotNull(true).setMandatory(true);
                userClass.createProperty("role", OType.STRING).setNotNull(true).setMandatory(true);
            }

            logger.debug("Schema verified for database: {}", session.getName());
        }

        createIndexes(dbName);
    }

    public void createIndexes(String dbName) {
        try (ODatabaseSession session = getSession(dbName)) {
            logger.info("Starting asynchronous index creation for database: {}", session.getName());

            // Location Index
            OClass locationClass = session.getClass("Location");
            if (locationClass != null && locationClass.getClassIndex("Location_customId_idx") == null) {
                locationClass.createIndex("Location_customId_idx", OClass.INDEX_TYPE.UNIQUE, "customId");
            }

            // Item Index
            OClass itemClass = session.getClass("Item");
            if (itemClass != null && itemClass.getClassIndex("Item_customId_idx") == null) {
                itemClass.createIndex("Item_customId_idx", OClass.INDEX_TYPE.UNIQUE, "customId");
            }

            // Conveyor Index (NEW)
            OClass conveyorClass = session.getClass("Conveyor");
            if (conveyorClass != null && conveyorClass.getClassIndex("Conveyor_customId_idx") == null) {
                conveyorClass.createIndex("Conveyor_customId_idx", OClass.INDEX_TYPE.UNIQUE, "customId");
            }

            // User Index
            OClass userClass = session.getClass("User");
            if (userClass != null && userClass.getClassIndex("User_username_idx") == null) {
                userClass.createIndex("User_username_idx", OClass.INDEX_TYPE.UNIQUE, "username");
            }

            logger.info("Asynchronous index creation finished for database: {}", session.getName());
        }
    }
}