package com.fiumen.backend.services;

import com.fiumen.backend.models.graph.GraphData;
import com.fiumen.backend.models.response.ConveyorResponse;
import com.fiumen.backend.models.response.ItemResponse;
import com.fiumen.backend.models.response.LocationResponse;
import com.fiumen.backend.repositories.LiveItemRepository;
import com.orientechnologies.orient.core.db.ODatabaseSession;
import com.orientechnologies.orient.core.sql.executor.OResult;
import com.orientechnologies.orient.core.sql.executor.OResultSet;
import fiumen.types.LocationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class GraphService {
    private static final Logger logger = LoggerFactory.getLogger(GraphService.class);

    private final OrientDBService orientDBService;
    private final LiveItemRepository redisRepository;

    public GraphService(OrientDBService orientDBService, LiveItemRepository redisRepository) {
        this.orientDBService = orientDBService;
        this.redisRepository = redisRepository;
    }

    public GraphData getGraphData() {
        Instant now = Instant.now();

        List<LocationResponse> nodes = new ArrayList<>();
        Map<String, ConveyorResponse> conveyorMap = new ConcurrentHashMap<>();
        List<ItemResponse> activeItems = new ArrayList<>();

        // 1. FETCH TOPOLOGY (From OrientDB)
        try (ODatabaseSession session = orientDBService.getSession()) {

            // A. Fetch Nodes (Waypoints)
            String nodeQuery = "SELECT customId, name, latitude, longitude, type, active, capacity, properties FROM Location";
            try (OResultSet rs = session.query(nodeQuery)) {
                while (rs.hasNext()) {
                    OResult res = rs.next();
                    LocationResponse loc = new LocationResponse();
                    loc.setId(res.getProperty("customId"));
                    loc.setName(res.getProperty("name"));
                    loc.setLatitude(res.getProperty("latitude"));
                    loc.setLongitude(res.getProperty("longitude"));
                    loc.setActive(res.getProperty("active"));
                    loc.setCapacity(res.getProperty("capacity"));
                    loc.setProperties(res.getProperty("properties"));

                    String typeStr = res.getProperty("type");
                    if (typeStr != null) {
                        try {
                            loc.setType(LocationType.valueOf(typeStr));
                        } catch (IllegalArgumentException e) {
                            loc.setType(LocationType.GENERIC);
                        }
                    }
                    nodes.add(loc);
                }
            }

            // B. Fetch Edges (Conveyors)
            // We need source and target IDs to build the graph visually
            String edgeQuery = "SELECT customId, length, speed, type, active, isMainPath, capacity, out.customId as src, in.customId as tgt FROM Conveyor";
            try (OResultSet rs = session.query(edgeQuery)) {
                while (rs.hasNext()) {
                    OResult res = rs.next();
                    ConveyorResponse conv = new ConveyorResponse();
                    conv.setId(res.getProperty("customId"));
                    conv.setSourceId(res.getProperty("src"));
                    conv.setTargetId(res.getProperty("tgt"));
                    conv.setLength(res.getProperty("length"));
                    conv.setSpeed(res.getProperty("speed"));
                    conv.setType(res.getProperty("type"));
                    conv.setActive(res.getProperty("active"));
                    conv.setIsMainPath(res.getProperty("isMainPath"));
                    conv.setCapacity(res.getProperty("capacity"));

                    conveyorMap.put(conv.getId(), conv);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Error fetching topology from OrientDB", e);
        }

        // 2. FETCH LIVE STATE (From Redis)
        List<Map<String, Object>> liveRawItems = redisRepository.getAllActiveItems();

        // 3. MERGE & SIMULATE (The "Ghost" Logic)
        for (Map<String, Object> rawItem : liveRawItems) {
            try {
                String id = (String) rawItem.get("id");
                String edgeId = (String) rawItem.get("edgeId");
                Long tsLong = (Long) rawItem.get("entryTimestamp");
                String destId = (String) rawItem.get("destinationId");

                // Optional: Name might be in Redis or we might need to fetch it.
                // For now assuming it's either in Redis or we send ID and frontend handles it.
                // If you stored 'n' in Redis, retrieve it here.

                if (edgeId == null || tsLong == null)
                    continue;

                Instant entryTime = Instant.ofEpochMilli(tsLong);

                // Parse Path (if available in Redis)
                // Assuming path is stored as List<String> or comma-separated string in Redis
                // For this example, we assume simple logic or that path is not strictly
                // required for static display
                List<String> path = new ArrayList<>(); // TODO: Parse from rawItem if you stored it

                // CALCULATE CURRENT POSITION
                // This handles the "Blind Spot" if the item moved while we weren't looking
                ItemResponse simulatedItem = calculateCurrentState(
                        id, edgeId, entryTime, path, conveyorMap, now);

                if (simulatedItem != null) {
                    simulatedItem.setDestinationId(destId);
                    // Set other metadata if available
                    activeItems.add(simulatedItem);
                }

            } catch (Exception e) {
                logger.warn("Failed to process live item state for item", e);
            }
        }

        return new GraphData(nodes, new ArrayList<>(conveyorMap.values()), activeItems);
    }

    /**
     * Performs "Dead Reckoning" to find where the item is RIGHT NOW.
     * It traverses the path based on speed/length and time elapsed.
     */
    private ItemResponse calculateCurrentState(
            String itemId,
            String startEdgeId,
            Instant entryTime,
            List<String> path,
            Map<String, ConveyorResponse> conveyorMap,
            Instant now) {

        Duration timeElapsed = Duration.between(entryTime, now);

        // Construct the full sequence of edges to check
        List<String> edgeSequence = new ArrayList<>();
        edgeSequence.add(startEdgeId);
        if (path != null)
            edgeSequence.addAll(path);

        for (String edgeId : edgeSequence) {
            ConveyorResponse conveyor = conveyorMap.get(edgeId);

            // If conveyor missing from map (topology changed?), abort
            if (conveyor == null)
                return null;

            double speed = (conveyor.getSpeed() != null) ? conveyor.getSpeed() : 0.0;
            double length = (conveyor.getLength() != null) ? conveyor.getLength() : 1.0;

            // Handle stopped belts or accumulation
            if (speed <= 0) {
                // Item is stuck here. Return 0% or last known progress.
                // For simplicity, we return it at start of this edge or max progress.
                return createItemResponse(itemId, edgeId, entryTime, 0.0);
            }

            long traversalTimeMillis = (long) ((length / speed) * 1000);
            Duration traversalDuration = Duration.ofMillis(traversalTimeMillis);

            // CHECK: Is the item still on this edge?
            if (timeElapsed.compareTo(traversalDuration) < 0) {
                // YES. Calculate progress.
                double progress = (double) timeElapsed.toMillis() / traversalTimeMillis;

                // We return the ItemResponse.
                // Note: We return the ORIGINAL entry time for this specific edge,
                // so the frontend can continue the animation smoothly.
                // We calculate the "Virtual Entry Time" for this edge:
                // VirtualEntry = Now - TimeElapsedOnThisEdge
                Instant virtualEntryTime = now.minus(timeElapsed);

                return createItemResponse(itemId, edgeId, virtualEntryTime, progress);
            }

            // NO. Item has finished this edge.
            // Subtract time and move to next edge in the loop.
            timeElapsed = timeElapsed.minus(traversalDuration);
        }

        // If we run out of path but still have time left, the item is waiting at the
        // end.
        String lastEdgeId = edgeSequence.get(edgeSequence.size() - 1);
        return createItemResponse(itemId, lastEdgeId, entryTime, 1.0);
    }

    private ItemResponse createItemResponse(String id, String edgeId, Instant entryTime, Double progress) {
        ItemResponse item = new ItemResponse();
        item.setId(id);
        item.setCurrentEdgeId(edgeId);
        item.setEntryTimestamp(entryTime);
        item.setProgress(Math.min(1.0, Math.max(0.0, progress)));
        item.setActive(true);
        return item;
    }
}