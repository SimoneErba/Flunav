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
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private static final String TEMPLATE_DB_NAME = "_template";
    private final Set<String> activeSimulations = ConcurrentHashMap.newKeySet();

    @PostConstruct
    public void init() {
        orientDB = new OrientDB(dbUrl, username, password, OrientDBConfig.defaultConfig());

        if (!orientDB.exists(mainDbName)) {
            orientDB.create(mainDbName, ODatabaseType.PLOCAL);
        }
        mainPool = new ODatabasePool(orientDB, mainDbName, username, password);
        try (ODatabaseSession session = getSession()) {
            ensureSchemaExists(session);
        }
        logger.info("OrientDB connection pool for main DB '{}' initialized.", mainDbName);

        if (!orientDB.exists(TEMPLATE_DB_NAME)) {
            // Create the "Golden Template" in-memory DB at startup for fast cloning.
            logger.info("Creating in-memory golden template database...");
            orientDB.create(TEMPLATE_DB_NAME, ODatabaseType.MEMORY);
            try (ODatabaseSession templateSession = orientDB.open(TEMPLATE_DB_NAME, username, password)) {
                ensureSchemaExists(templateSession);
            }
            logger.info("Golden template database '{}' created and configured.", TEMPLATE_DB_NAME);
        }
    }

    @PreDestroy
    public void close() {
        if (mainPool != null) mainPool.close();
        if (orientDB != null) {
            activeSimulations.forEach(this::dropDatabase);
            if (orientDB.exists(TEMPLATE_DB_NAME)) orientDB.drop(TEMPLATE_DB_NAME);
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

        if (simulationId != null) {
            // A simulation context is active for this thread. Connect to the in-memory DB.
            if (!activeSimulations.contains(simulationId)) {
                throw new IllegalStateException("Attempted to get session for non-existent or inactive simulation: " + simulationId);
            }
            return orientDB.open(simulationId, username, password);
        } else {
            // No simulation context. Default to the pooled connection for the live database.
            return mainPool.acquire();
        }
    }

    /**
     * Creates a new in-memory database by cloning the golden template.
     */
    public void createInMemoryDatabase(String dbName) {
        Path tempBackupFile = null;
        try {
            tempBackupFile = Files.createTempFile("orientdb_template_backup", ".zip");
            String backupPath = tempBackupFile.toAbsolutePath().toString();

            try (ODatabaseSession templateSession = orientDB.open(TEMPLATE_DB_NAME, username, password)) {
                templateSession.command(String.format("BACKUP DATABASE %s", backupPath));
            }

            if (orientDB.exists(dbName)) orientDB.drop(dbName);
            orientDB.create(dbName, ODatabaseType.MEMORY);

            try (ODatabaseSession newDbSession = orientDB.open(dbName, username, password)) {
                newDbSession.command(String.format("RESTORE DATABASE %s", backupPath));
            }
            
            activeSimulations.add(dbName);
            logger.info("Successfully cloned new in-memory simulation database: {}", dbName);

        } catch (IOException e) {
            throw new RuntimeException("Simulation DB creation failed due to temp file issue.", e);
        } finally {
            if (tempBackupFile != null) {
                try {
                    Files.deleteIfExists(tempBackupFile);
                } catch (IOException e) {
                    logger.warn("Could not delete temporary backup file: {}", tempBackupFile, e);
                }
            }
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

    private void ensureSchemaExists(ODatabaseSession session) {
        if (session.getClass("Location") == null) {
            OClass locationClass = session.createVertexClass("Location");
            locationClass.createProperty("customId", OType.STRING).setNotNull(true);
            locationClass.createIndex("Location_customId_idx", OClass.INDEX_TYPE.UNIQUE, "customId");
        }
        if (session.getClass("Item") == null) {
            OClass itemClass = session.createVertexClass("Item");
            itemClass.createProperty("customId", OType.STRING).setNotNull(true);
            itemClass.createIndex("Item_customId_idx", OClass.INDEX_TYPE.UNIQUE, "customId");
        }
        if (session.getClass("HasPosition") == null) session.createEdgeClass("HasPosition");
        if (session.getClass("ConnectedTo") == null) session.createEdgeClass("ConnectedTo");
        
        logger.debug("Schema verified for database: {}", session.getName());
    }

    @FunctionalInterface
    public interface SessionCallback {
        void execute(ODatabaseSession session);
    }
}