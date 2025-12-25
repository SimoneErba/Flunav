package com.flonav.backend.services;

import com.flonav.backend.context.DatabaseContextHolder;
import com.orientechnologies.orient.core.db.ODatabasePool;
import com.orientechnologies.orient.core.db.ODatabaseSession;
import com.orientechnologies.orient.core.db.ODatabaseType;
import com.orientechnologies.orient.core.db.OrientDB;
import com.orientechnologies.orient.core.db.OrientDBConfig;
import com.orientechnologies.orient.core.metadata.schema.OClass;
import com.orientechnologies.orient.core.metadata.schema.OType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OrientDBService {
    private static final Logger logger = LoggerFactory.getLogger(OrientDBService.class);

    private OrientDB orientDB;
    private ODatabasePool mainPool;

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
        mainPool = new ODatabasePool(orientDB, mainDbName, username, password);
        ensureSchemaExists();

        logger.info("OrientDB connection pool for main DB '{}' initialized.", mainDbName);
    }

    @PreDestroy
    public void close() {
        if (mainPool != null)
            mainPool.close();
        if (orientDB != null) {
            activeSimulations.forEach(this::dropDatabase);
            orientDB.close();
        }
        logger.info("OrientDB service has been shut down.");
    }

    public ODatabaseSession getSession() {
        ODatabaseSession activeSession = transactionalSession.get();

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
                        return method.invoke(activeSession, args);
                    });
        }

        String simulationId = DatabaseContextHolder.getSimulationId();
        if (simulationId != null) {
            if (!activeSimulations.contains(simulationId)) {
                throw new IllegalStateException(
                        "Attempted to get session for non-existent or inactive simulation: " + simulationId);
            }
            logger.trace("Opening new session for simulation DB: {}", simulationId);
            return orientDB.open(simulationId, username, password);
        } else {
            logger.trace("Acquiring new session for main DB from pool.");
            return mainPool.acquire();
        }
    }

    public ODatabaseSession getSession(String dbName) {
        if (dbName != null) {
            return orientDB.open(dbName, username, password);
        } else {
            return mainPool.acquire();
        }
    }

    public void withTransaction(TransactionalCallback callback) {
        try (ODatabaseSession session = getSession()) {
            transactionalSession.set(session);
            try {
                session.begin();
                callback.execute(session);
                session.commit();
                logger.debug("Transaction committed successfully.");
            } catch (Exception e) {
                logger.error("Error during transactional callback. Initiating rollback.", e);
                if (!session.isClosed() && session.getTransaction().isActive()) {
                    try {
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
        try (ODatabaseSession session = getSession()) {
            callback.execute(session);
        } catch (Exception e) {
            logger.error("Error executing session callback on context-aware DB", e);
            throw new RuntimeException("Session callback failed", e);
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
            activeSimulations.add(dbName);
            logger.info("Successfully created new in-memory simulation database: {}", dbName);
            try (var context = DatabaseContextHolder.enterSimulationContext(dbName)) {
                ensureSchemaExists();
            }

        } catch (Exception e) {
            logger.error("Failed to create in-memory simulation DB '{}'", dbName, e);
            activeSimulations.remove(dbName);

            if (orientDB.exists(dbName)) {
                orientDB.drop(dbName);
            }
            throw new RuntimeException("Simulation DB creation failed.", e);
        }
    }

    public void dropDatabase(String dbName) {
        if (orientDB.exists(dbName)) {
            orientDB.drop(dbName);
            activeSimulations.remove(dbName);
            logger.info("Dropped simulation database: {}", dbName);
        }
    }

    private void ensureSchemaExists() {
        try (ODatabaseSession session = getSession()) {
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

            logger.debug("Schema verified for database: {}", session.getName());
        }

        createIndexes(DatabaseContextHolder.getSimulationId());
    }

    @Async
    public void createIndexes(String simulationId) {
        OrientDB localOrientDB = new OrientDB(dbUrl, username, password, OrientDBConfig.defaultConfig());
        try (ODatabaseSession session = localOrientDB.open(simulationId != null ? simulationId : mainDbName, username,
                password)) {
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

            logger.info("Asynchronous index creation finished for database: {}", session.getName());
        } finally {
            localOrientDB.close();
        }
    }
}