package com.flumen.backend.services;

import com.flumen.backend.models.response.ItemResponse;
import com.flumen.backend.models.Location;
import com.flumen.backend.entities.ConnectedTo;
import com.flumen.backend.models.graph.GraphData;
import com.flumen.backend.models.response.ConnectionResponse;
import com.flumen.backend.models.response.LocationResponse;
import com.flumen.backend.services.ClickHouseService.Snapshot;
import com.orientechnologies.orient.core.db.ODatabaseSession;
import com.orientechnologies.orient.core.record.OEdge;
import com.orientechnologies.orient.core.record.OVertex;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.sql.executor.OResultSet;

import flumen.events.DomainEvent;

import com.orientechnologies.orient.core.id.ORID;

import org.modelmapper.ModelMapper;
import org.modelmapper.convention.MatchingStrategies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Lazy;
import org.springframework.lang.NonNull;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class GraphService implements ApplicationContextAware {
    private final OrientDBService orientDBService;
    private final ClickHouseService clickHouseService;
    private final EventProcessor eventProcessor;
    private RabbitListenerEndpointRegistry rabbitListenerRegistry;
    private final ModelMapper modelMapper;
    private static final Logger logger = LoggerFactory.getLogger(GraphService.class);

    @Override
    public void setApplicationContext(@NonNull ApplicationContext applicationContext) {
        this.rabbitListenerRegistry = applicationContext.getBean(RabbitListenerEndpointRegistry.class);
    }

    public GraphService(OrientDBService orientDBService, ClickHouseService clickHouseService, @Lazy EventProcessor eventProcessor) {
        this.orientDBService = orientDBService;
        this.clickHouseService = clickHouseService;
        this.eventProcessor = eventProcessor;
        this.modelMapper = new ModelMapper();
        modelMapper.getConfiguration().setMatchingStrategy(MatchingStrategies.STRICT);
        
        // Configure custom mappings if needed
        modelMapper.createTypeMap(Location.class, LocationResponse.class);
        modelMapper.createTypeMap(ConnectedTo.class, ConnectionResponse.class);
    }

    public GraphData getGraphData() {
        GraphData graphData = new GraphData();
        List<LocationResponse> locations = new ArrayList<>();
        
        try (ODatabaseSession session = orientDBService.getSession()) {
            String query = "SELECT *, in('HasPosition'):{*, @rid} as items, in('ConnectedTo'):{@rid, *} as in, out('ConnectedTo'):{*, @rid} as out FROM Location";
            
            try (OResultSet rs = session.query(query)) {
                while (rs.hasNext()) {
                    OVertex result = rs.next().toElement().asVertex().get();
                    LocationResponse location = new LocationResponse();
                    location.setId(result.getProperty("customId"));
                    location.setName(result.getProperty("name"));
                    location.setLatitude(result.getProperty("latitude"));
                    location.setLongitude(result.getProperty("longitude"));
                    location.setLength(((Number) result.getProperty("length")).doubleValue());
                    location.setSpeed(((Number) result.getProperty("speed")).doubleValue());
                    location.setActive(result.getProperty("active"));
                    
                    location.setProperties(result.getProperty("properties"));
                    
                    // Process items
                    List<ODocument> itemResults = result.getProperty("items");
                    if (itemResults != null) {
                        List<ItemResponse> locationItems = itemResults.stream()
                            .map(itemResult -> {
                                ItemResponse item = new ItemResponse();
                                item.setId(itemResult.field("customId"));
                                item.setName(itemResult.field("name"));
                                item.setSpeed(itemResult.field("speed"));
                                item.setActive(itemResult.field("active"));
                                item.setProperties(itemResult.field("properties"));
                                return item;
                            })
                            .collect(Collectors.toList());
                        location.setItems(locationItems);
                    }
                    
                    List<ConnectionResponse> locationConnections = new ArrayList<>();
                    
                    // Process outgoing connections
                    List<ODocument> outResults = result.getProperty("out");
                    if (outResults != null) {
                        outResults.forEach(outResult -> {
                            ConnectionResponse conn = new ConnectionResponse();
                            conn.setSourceId(location.getId());
                            conn.setTargetId(outResult.field("customId"));
                            conn.setDirection("out");
                        
                            conn.setProperties(outResult.field("properties"));
  
                            locationConnections.add(conn);
                        });
                    }
                    
                    // Process incoming connections
                    List<ODocument> inResults = result.getProperty("in");
                    if (inResults != null) {
                        inResults.forEach(inResult -> {
                            ConnectionResponse conn = new ConnectionResponse();
                            conn.setSourceId(inResult.field("customId"));
                            conn.setTargetId(location.getId());
                            conn.setDirection("in");

                            conn.setProperties(inResult.field("properties"));
                            locationConnections.add(conn);
                        });
                    }
                    
                    location.setConnections(locationConnections);
                    locations.add(location);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Error fetching graph data", e);
        }
        
        // Convert locations to responses using ModelMapper
        List<LocationResponse> locationResponses = locations.stream()
            .map(location -> modelMapper.map(location, LocationResponse.class))
            .collect(Collectors.toList());
        graphData.setLocations(locationResponses);
        
        // Collect all connections and convert using ModelMapper
        List<ConnectionResponse> allConnections = locations.stream()
            .flatMap(location -> location.getConnections().stream())
            .filter(connection -> connection.getDirection().equals("in"))
            .map(connection -> {
                ConnectionResponse response = modelMapper.map(connection, ConnectionResponse.class);
                // Ensure IDs are properly set and not Optional.empty
                response.setSourceId(connection.getSourceId());
                response.setTargetId(connection.getTargetId());
                return response;
            })
            .collect(Collectors.toList());
        graphData.setConnections(allConnections);
        
        return graphData;
    }

    public void rebuildGraphState(Instant timeToRestore) {
        logger.info("Starting graph state reconstruction...");
        MessageListenerContainer listenerContainer = rabbitListenerRegistry.getListenerContainer("graph-state-events-listener");

        try{
            logger.warn("Pausing RabbitMQ listener 'graph-state-events-listener'. Incoming events will be queued.");
            listenerContainer.stop();
            
            Optional<Snapshot> snapshotOpt = clickHouseService.getMostRecentSnapshotBefore(timeToRestore);

            Instant startTime = Instant.EPOCH;

            if (snapshotOpt.isPresent()) {
                Snapshot snapshot = snapshotOpt.get();
                logger.info("Restoring state from snapshot taken at {}", snapshot.timestamp());
                restoreFromSnapshotData(snapshot.graphData());
                startTime = snapshot.timestamp();
            } else {
                logger.info("No snapshot found. Replaying all events from the beginning.");
            }

            List<DomainEvent> eventsToReplay = clickHouseService.getEventsBetween(startTime, timeToRestore);
            logger.info("Found {} events to replay.", eventsToReplay.size());

            for (DomainEvent event : eventsToReplay) {
                eventProcessor.process(event, false); 
            }

            logger.info("Graph state reconstruction complete.");
        } catch (Exception e) {
            logger.error("A critical error occurred during the rebuild process.", e);
        } finally {
            if (listenerContainer != null && !listenerContainer.isRunning()) {
                logger.warn("Resuming RabbitMQ listener 'graph-state-events-listener'. It will now process queued messages.");
                listenerContainer.start();
            }
        }
    }

    /**
     * Restores the entire graph state in OrientDB from a given snapshot.
     * THIS IS A DESTRUCTIVE OPERATION. It will first wipe the entire graph
     * (Items, Locations, and their connections) before recreating it from the snapshot data.
     * The entire process is wrapped in a transaction to ensure atomicity.
     *
     * @param graphData The snapshot data to restore.
     */
    public void restoreFromSnapshotData(GraphData graphData) {
        logger.warn("Executing destructive snapshot restore. Wiping current graph state.");

        try (ODatabaseSession session = orientDBService.getSession()) {
            try {
                session.begin();
                logger.info("Transaction started for snapshot restore.");

                clearDatabase(session);

                Map<String, ORID> locationIdToRidMap = new HashMap<>();
                Map<String, ORID> itemIdToRidMap = new HashMap<>();

                logger.info("Creating {} locations from snapshot...", graphData.getLocations().size());
                for (LocationResponse locData : graphData.getLocations()) {
                    OVertex locationVertex = session.newVertex("Location");
                    locationVertex.setProperty("customId", locData.getId());
                    locationVertex.setProperty("name", locData.getName());
                    locationVertex.setProperty("latitude", locData.getLatitude());
                    locationVertex.setProperty("longitude", locData.getLongitude());
                    locationVertex.setProperty("length", locData.getLength());
                    locationVertex.setProperty("speed", locData.getSpeed());
                    locationVertex.setProperty("active", locData.getActive());
                    locationVertex.setProperty("properties", locData.getProperties());
                    locationVertex.save();
                    locationIdToRidMap.put(locData.getId(), locationVertex.getIdentity());

                    if (locData.getItems() != null) {
                        for (ItemResponse itemData : locData.getItems()) {
                            OVertex itemVertex = session.newVertex("Item");
                            itemVertex.setProperty("customId", itemData.getId());
                            itemVertex.setProperty("name", itemData.getName());
                            itemVertex.setProperty("speed", itemData.getSpeed());
                            itemVertex.setProperty("active", itemData.getActive());
                            itemVertex.setProperty("properties", itemData.getProperties());
                            itemVertex.save();
                            itemIdToRidMap.put(itemData.getId(), itemVertex.getIdentity());

                            itemVertex.addEdge(locationVertex, "HasPosition").save();
                        }
                    }
                }
                logger.info("Vertex creation complete.");

                logger.info("Creating {} connections from snapshot...", graphData.getConnections().size());
                for (ConnectionResponse connData : graphData.getConnections()) {
                    ORID sourceRid = locationIdToRidMap.get(connData.getSourceId());
                    ORID targetRid = locationIdToRidMap.get(connData.getTargetId());

                    if (sourceRid != null && targetRid != null) {
                        OVertex sourceVertex = (OVertex) session.load(sourceRid);
                        OVertex targetVertex = (OVertex) session.load(targetRid);
                        OEdge connectionEdge = sourceVertex.addEdge(targetVertex, "ConnectedTo");
                        connectionEdge.setProperty("properties", connData.getProperties());
                        connectionEdge.save();
                    } else {
                        logger.warn("Could not create connection from {} to {}: one or both locations not found in map.",
                                connData.getSourceId(), connData.getTargetId());
                    }
                }
                logger.info("Edge creation complete.");

                session.commit();
                logger.info("Snapshot restore transaction committed successfully.");

            } catch (Exception e) {
                logger.error("Error during snapshot restore. Rolling back transaction.", e);
                session.rollback();
                throw new RuntimeException("Snapshot restore failed and was rolled back.", e);
            }
        }
    }

    /**
     * Wipes all relevant graph data from the database.
     * This should only be called within a transaction.
     *
     * @param session The active ODatabaseSession.
     */
    private void clearDatabase(ODatabaseSession session) {
        logger.info("Clearing database: Deleting all edges and vertices...");

        session.command("DELETE FROM ConnectedTo UNSAFE");
        session.command("DELETE FROM HasPosition UNSAFE");
        session.command("DELETE FROM Item UNSAFE");
        session.command("DELETE FROM Location UNSAFE");

        logger.info("Database clearing complete.");
    }
}