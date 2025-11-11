package com.fiumen.backend.services;

import com.fiumen.backend.models.graph.*;
import com.fiumen.backend.models.response.ConnectionResponse;
import com.fiumen.backend.models.response.ItemJourney;
import com.fiumen.backend.models.response.ItemResponse;
import com.fiumen.backend.models.response.LocationResponse;
import com.orientechnologies.orient.core.db.ODatabaseSession;
import com.orientechnologies.orient.core.sql.executor.OResult;
import com.orientechnologies.orient.core.sql.executor.OResultSet;

import fiumen.types.LocationType;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class GraphService {
    private final OrientDBService orientDBService;

    public GraphService(OrientDBService orientDBService) {
        this.orientDBService = orientDBService;
    }

    public GraphData getGraphData() {
        Instant simulationTime = Instant.now();
        
        Map<String, LocationResponse> locationsMap = new ConcurrentHashMap<>();
        List<ConnectionResponse> allConnections = new ArrayList<>();
        List<ItemResponse> allItems = new ArrayList<>();

        try (ODatabaseSession session = orientDBService.getSession()) {
            String query = "SELECT *, " +
                           "in('HasPosition'):{*, customId, name, destinations, lastConfirmationTimestamp} as items, " +
                           "out('ConnectedTo').customId as out_connections " +
                           "FROM Location";
            
            try (OResultSet rs = session.query(query)) {
                while (rs.hasNext()) {
                    OResult result = rs.next();
                    String locId = result.getProperty("customId");
                    if (locId == null) continue; // Skip malformed records

                    // --- Process Location with Explicit Mapping ---
                    LocationResponse location = new LocationResponse();
                    location.setId(locId);
                    location.setName(result.getProperty("name"));
                    location.setLatitude(result.getProperty("latitude"));
                    location.setLongitude(result.getProperty("longitude"));
                    location.setActive(result.getProperty("active"));
                    location.setProperties(result.getProperty("properties"));
                    
                    // Safely cast numeric types
                    Number length = result.getProperty("length");
                    location.setLength(length != null ? length.doubleValue() : 0.0);

                    Number speed = result.getProperty("speed");
                    location.setSpeed(speed != null ? speed.doubleValue() : 0.0);
                    
                    Number capacity = result.getProperty("capacity");
                    location.setCapacity(capacity != null ? capacity.intValue() : -1);

                    // Assuming LocationType is an enum stored as a string
                    String typeStr = result.getProperty("type");
                    if (typeStr != null) {
                        try {
                            location.setType(LocationType.valueOf(typeStr.toUpperCase()));
                        } catch (IllegalArgumentException e) {
                            // Log a warning for unknown types
                            System.err.println("Warning: Unknown LocationType '" + typeStr + "' for location " + locId);
                        }
                    }
                    
                    locationsMap.put(locId, location);

                    // --- Process Outgoing Connections ---
                    List<String> outConnections = result.getProperty("out_connections");
                    if (outConnections != null) {
                        for (String targetId : outConnections) {
                            ConnectionResponse conn = new ConnectionResponse();
                            conn.setSourceId(locId);
                            conn.setTargetId(targetId);
                            conn.setDirection("out");
                            allConnections.add(conn);
                        }
                    }

                    // --- Process Items at this Location ---
                    List<OResult> itemResults = result.getProperty("items");
                    if (itemResults != null) {
                        for (OResult itemResult : itemResults) {
                            ItemResponse item = new ItemResponse();
                            item.setId(itemResult.getProperty("customId"));
                            item.setName(itemResult.getProperty("name"));
                            item.setLastKnownLocationId(locId);
                            item.setDestinations(itemResult.getProperty("destinations"));
                            
                            Date timestamp = itemResult.getProperty("lastConfirmationTimestamp");
                            if (timestamp != null) {
                                item.setLastConfirmationTimestamp(timestamp.toInstant());
                            }
                            allItems.add(item);
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Error fetching graph data from OrientDB", e);
        }

        // --- Simulation and Final Assembly (No changes needed here) ---

        allItems.parallelStream().forEach(item -> {
            ItemJourney journey = calculateCurrentJourney(item, locationsMap, simulationTime);
            item.setCurrentJourney(journey);
        });
        
        Map<String, List<ItemResponse>> finalItemsByLocation = allItems.stream()
            .filter(item -> item.getLastKnownLocationId() != null)
            .collect(Collectors.groupingBy(ItemResponse::getLastKnownLocationId));
            
        locationsMap.values().forEach(loc -> loc.setItems(finalItemsByLocation.getOrDefault(loc.getId(), Collections.emptyList())));

        return new GraphData(new ArrayList<>(locationsMap.values()), allConnections);
    }
    
    private ItemJourney calculateCurrentJourney(
        ItemResponse item, Map<String, LocationResponse> locationMap, Instant simulationTime
    ) {
        String lastKnownLocationId = item.getLastKnownLocationId();
        Instant lastConfirmationTime = item.getLastConfirmationTimestamp();

        if (lastKnownLocationId == null || lastConfirmationTime == null) return null;

        Duration totalTimeElapsed = Duration.between(lastConfirmationTime, simulationTime);
        
        List<String> destinations = item.getDestinations();
        List<String> path = new ArrayList<>();
        
        if (destinations != null && !destinations.isEmpty()) {
            path.addAll(destinations);
        } else {
            return null; // No path to simulate
        }

        String currentLocId = lastKnownLocationId;
        for (int i = 0; i < path.size(); i++) {
            String targetLocId = path.get(i);
            
            LocationResponse sourceLocation = locationMap.get(currentLocId);
            if (sourceLocation == null || sourceLocation.getSpeed() <= 0 || sourceLocation.getLength() <= 0) {
                break; 
            }

            Duration timeToTraverse = Duration.ofMillis(
                (long) ((sourceLocation.getLength() / sourceLocation.getSpeed()) * 1000)
            );

            if (totalTimeElapsed.compareTo(timeToTraverse) < 0) {
                double progress = (double) totalTimeElapsed.toMillis() / timeToTraverse.toMillis();
                return new ItemJourney(currentLocId, targetLocId, progress, lastConfirmationTime);
            }

            totalTimeElapsed = totalTimeElapsed.minus(timeToTraverse);
            lastConfirmationTime = lastConfirmationTime.plus(timeToTraverse);
            currentLocId = targetLocId;
        }

        return null;
    }
}