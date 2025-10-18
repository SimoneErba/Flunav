package com.flumen.backend.services;

import com.flumen.backend.context.DatabaseContextHolder;
import com.orientechnologies.orient.core.db.ODatabaseDocumentInternal;
import com.orientechnologies.orient.core.db.ODatabasePool;
import com.orientechnologies.orient.core.db.ODatabaseRecordThreadLocal;
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
     * It checks the DatabaseContextHolder to determine whether to connect to a
     * transient simulation DB or the main, live database.
     */
    public ODatabaseSession getSession() {
        String simulationId = DatabaseContextHolder.getSimulationId();
        logger.info("---------------------Simulation is: " + simulationId);
        if (simulationId != null) {
            if (!activeSimulations.contains(simulationId)) {
                throw new IllegalStateException("Attempted to get session for non-existent or inactive simulation: " + simulationId);
            }
            return orientDB.open(simulationId, username, password);
        } else {
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


    /**
     * Creates a new, ready-to-use in-memory database and applies the base schema.
     * The creation of indexes is triggered to run in the background.
     */
    public void createInMemoryDatabase(String dbName) {
        try {
            if (orientDB.exists(dbName)) {
                orientDB.drop(dbName);
            }
            orientDB.create(dbName, ODatabaseType.MEMORY);
            activeSimulations.add(dbName);
            logger.info("Successfully created new in-memory simulation database: {}", dbName);
            DatabaseContextHolder.setSimulationId(dbName);
            ensureSchemaExists();

        } catch (Exception e) {
            logger.error("Failed to create in-memory simulation DB '{}'", dbName, e);
            activeSimulations.remove(dbName);

            if (orientDB.exists(dbName)) {
                orientDB.drop(dbName);
            }
            throw new RuntimeException("Simulation DB creation failed.", e);
        } finally{
            DatabaseContextHolder.clear();
        }
    }

    public void dropDatabase(String dbName) {
        if (orientDB.exists(dbName)) {
            orientDB.drop(dbName);
            activeSimulations.remove(dbName);
            logger.info("Dropped simulation database: {}", dbName);
        }
    }
    
    /**
     * Utility method to execute a block of code within a managed, context-aware session.
     */
    public void withSession(SessionCallback callback) {
        try (ODatabaseSession session = getSession()) {
            callback.execute(session);
        } catch (Exception e) {
            logger.error("Error executing session callback on context-aware DB", e);
            throw new RuntimeException("Session callback failed", e);
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
        orientDB = new OrientDB(dbUrl, username, password, OrientDBConfig.defaultConfig());
        try (ODatabaseSession session = orientDB.open(simulationId != null ? simulationId : mainDbName, username, password)) {
            logger.info("Starting asynchronous index creation for database: {}", dbUrl);
                
            OClass locationClass = session.getClass("Location");
            if (locationClass != null && locationClass.getClassIndex("Location_customId_idx") == null) {
                logger.info("Creating index 'Location_customId_idx'...");
                locationClass.createIndex("Location_customId_idx", OClass.INDEX_TYPE.UNIQUE, "customId");
                logger.info("Index 'Location_customId_idx' created.");
            }

            OClass itemClass = session.getClass("Item");
            if (itemClass != null && itemClass.getClassIndex("Item_customId_idx") == null) {
                logger.info("Creating index 'Item_customId_idx'...");
                itemClass.createIndex("Item_customId_idx", OClass.INDEX_TYPE.UNIQUE, "customId");
                logger.info("Index 'Item_customId_idx' created.");
            }
            
            logger.info("Asynchronous index creation finished for database: {}", dbUrl);
        }
    }


    @FunctionalInterface
    public interface SessionCallback {
        void execute(ODatabaseSession session);
    }
}