package com.flumen.backend.services;

import com.flumen.backend.context.DatabaseContextHolder;
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

    // --- NEW: A ThreadLocal specifically for managing an active transactional session ---
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
        if (mainPool != null) mainPool.close();
        if (orientDB != null) {
            activeSimulations.forEach(this::dropDatabase);
            orientDB.close();
        }
        logger.info("OrientDB service has been shut down.");
    }

    /**
     * The primary, context-aware method for acquiring a database session.
     * --- REFACTORED ---
     * It now prioritizes reusing an existing transactional session from the ThreadLocal context.
     * If none exists, it falls back to the original logic of checking the simulationId.
     * This is the core of the transaction propagation pattern.
     */
    public ODatabaseSession getSession() {
        // 1. Check for an active transactional session on this thread first.
        ODatabaseSession session = transactionalSession.get();
        if (session != null && !session.isClosed()) {
            logger.trace("Reusing existing transactional session for this thread.");
            return session;
        }

        // 2. If no transactional session, fall back to the normal context-aware logic.
        String simulationId = DatabaseContextHolder.getSimulationId();
        if (simulationId != null) {
            if (!activeSimulations.contains(simulationId)) {
                throw new IllegalStateException("Attempted to get session for non-existent or inactive simulation: " + simulationId);
            }
            logger.trace("Opening new session for simulation DB: {}", simulationId);
            return orientDB.open(simulationId, username, password);
        } else {
            logger.trace("Acquiring new session for main DB from pool.");
            return mainPool.acquire();
        }
    }

    public ODatabaseSession getSession(String dbName) {
        // This method remains useful for direct access when needed.
        if (dbName != null) {
            return orientDB.open(dbName, username, password);
        } else {
            return mainPool.acquire();
        }
    }

    /**
     * --- NEW: The robust "Unit of Work" method for batch operations. ---
     * This method manages the entire lifecycle of a transaction.
     * It acquires a new session, binds it to the ThreadLocal context,
     * executes the callback, and guarantees commit/rollback and cleanup.
     */
    public void withTransaction(TransactionalCallback callback) {
        // We always start with a fresh session for a transaction to ensure isolation.
        try (ODatabaseSession session = getSession()) {
            // Bind this session to the current thread for the duration of the transaction.
            transactionalSession.set(session);
            
            try {
                session.begin();
                callback.execute(session);
                session.commit();
                logger.debug("Transaction committed successfully.");
            } catch (Exception e) {
                logger.error("Error during transactional callback. Initiating rollback.", e);
                if (session.getTransaction().isActive()) {
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
            // CRITICAL: Always clear the ThreadLocal after the transaction is complete.
            transactionalSession.remove();
        }
    }

    /**
     * Utility method to execute a block of code within a managed, single-use session.
     * --- REFACTORED ---
     * This is now intended for non-transactional or single-statement operations.
     * It's a simple wrapper for getting and closing a session.
     */
    public void withSession(SessionCallback callback) {
        // The getSession() call here will now correctly check the ThreadLocal first.
        // If this is called *inside* a withTransaction block, it will reuse the session.
        // If called standalone, it will create a new one.
        try (ODatabaseSession session = getSession()) {
            callback.execute(session);
        } catch (Exception e) {
            logger.error("Error executing session callback on context-aware DB", e);
            throw new RuntimeException("Session callback failed", e);
        }
    }

    // --- NEW: Functional interface for the transactional method ---
    @FunctionalInterface
    public interface TransactionalCallback {
        void execute(ODatabaseSession session);
    }
    
    // Existing functional interface, still useful.
    @FunctionalInterface
    public interface SessionCallback {
        void execute(ODatabaseSession session);
    }

    // --- The rest of your service methods are unchanged, but I've included them for completeness ---

    public void createInMemoryDatabase(String dbName) {
        try {
            if (orientDB.exists(dbName)) {
                orientDB.drop(dbName);
            }
            orientDB.create(dbName, ODatabaseType.MEMORY);
            activeSimulations.add(dbName);
            logger.info("Successfully created new in-memory simulation database: {}", dbName);
            try(var context = DatabaseContextHolder.enterSimulationContext(dbName))
            {
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
            if (session.getClass("Location") == null) {
                OClass locationClass = session.createVertexClass("Location");
                locationClass.createProperty("customId", OType.STRING).setNotNull(true);
            }
            if (session.getClass("Item") == null) {
                OClass itemClass = session.createVertexClass("Item");
                itemClass.createProperty("customId", OType.STRING).setNotNull(true);
            }
            if (session.getClass("HasPosition") == null) session.createEdgeClass("HasPosition");
            if (session.getClass("ConnectedTo") == null) session.createEdgeClass("ConnectedTo");
            logger.debug("Schema verified for database: {}", session.getName());
        }

        createIndexes(DatabaseContextHolder.getSimulationId());
    }

    @Async
    public void createIndexes(String simulationId) {
        // Note: Creating a new OrientDB instance in an @Async method can be tricky.
        // This is okay for a one-off task, but for heavy use, consider passing the OrientDB factory bean.
        OrientDB localOrientDB = new OrientDB(dbUrl, username, password, OrientDBConfig.defaultConfig());
        try (ODatabaseSession session = localOrientDB.open(simulationId != null ? simulationId : mainDbName, username, password)) {
            logger.info("Starting asynchronous index creation for database: {}", session.getName());
                
            OClass locationClass = session.getClass("Location");
            if (locationClass != null && locationClass.getClassIndex("Location_customId_idx") == null) {
                locationClass.createIndex("Location_customId_idx", OClass.INDEX_TYPE.UNIQUE, "customId");
            }

            OClass itemClass = session.getClass("Item");
            if (itemClass != null && itemClass.getClassIndex("Item_customId_idx") == null) {
                itemClass.createIndex("Item_customId_idx", OClass.INDEX_TYPE.UNIQUE, "customId");
            }
            
            logger.info("Asynchronous index creation finished for database: {}", session.getName());
        } finally {
            localOrientDB.close();
        }
    }
}