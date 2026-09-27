package com.flunav.backend.services;

import com.flunav.backend.models.RedisLiveItem;
import com.flunav.backend.models.response.ConveyorResponse;
import com.flunav.backend.models.response.ItemResponse;
import com.flunav.backend.models.response.LocationResponse;
import com.flunav.backend.repositories.LiveItemRepository;
import com.flunav.backend.repositories.LiveConveyorRepository;
import flunav.types.ConveyorType;
import flunav.types.PositionType;
import flunav.types.RoutingStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Projects context-scoped Redis hot positions at a requested domain timestamp. */
@Service
public class ItemPositionProjection {
    private static final Logger logger = LoggerFactory.getLogger(ItemPositionProjection.class);
    private final LiveItemRepository redisRepository;
    private final LiveConveyorRepository liveConveyorRepository;
    private final PathfindingService pathfindingService;
    private final DestinationMappingService destinationMappingService;

    public ItemPositionProjection(LiveItemRepository redisRepository, LiveConveyorRepository liveConveyorRepository,
            PathfindingService pathfindingService, DestinationMappingService destinationMappingService) {
        this.redisRepository = redisRepository;
        this.liveConveyorRepository = liveConveyorRepository;
        this.pathfindingService = pathfindingService;
        this.destinationMappingService = destinationMappingService;
    }

    /** Redis reads inherit the caller's live or simulation context; only live reads clean expired items. */
    List<ItemResponse> calculateAllItemStates(GraphService.Topology topology, Instant now, boolean shouldCleanup,
            String simulationId, boolean includeFinished,
            Map<String, Map<String, Object>> itemPropertiesMap, Map<String, Double> itemPriorities) {
        List<RedisLiveItem> liveRawItems = redisRepository.getAllActiveItems();
        Map<String, Integer> stagingOrders = new HashMap<>();
        Map<String, Double> stagedDistances = calculateStagedDistances(liveRawItems, topology, now, stagingOrders);

        List<ItemResponse> activeItems = new ArrayList<>();
        List<String> itemsToRemove = new ArrayList<>();

        for (RedisLiveItem rawItem : liveRawItems) {
            String id = rawItem.getId();
            try {
                String positionId = rawItem.getPositionId();
                PositionType type = rawItem.getType();
                if (type == null)
                    type = PositionType.LOCATION;

                Instant entryTime = rawItem.getEntryTime();
                List<String> destinations = rawItem.getDestinations();
                String selectedExitId = rawItem.getSelectedExitId();
                RoutingStatus routingStatus = effectiveRoutingStatus(rawItem.getRoutingStatus(), selectedExitId);
                Instant routingStatusUpdatedAt = rawItem.getRoutingStatusUpdatedAt();
                Double accDist = rawItem.getAccumulatedDistance();

                if (positionId == null || entryTime == null)
                    continue;

                List<String> path = rawItem.getPath();

                // --- PATHFINDING (If missing) ---
                if (path == null && selectedExitId != null) {
                    String startNode = null;
                    if (type == PositionType.LOCATION) {
                        startNode = positionId;
                    } else if (type == PositionType.CONVEYOR && topology.conveyorMap().containsKey(positionId)) {
                        startNode = topology.conveyorMap().get(positionId).getTargetId();
                    }

                    if (startNode != null) {
                        path = pathfindingService.calculateShortestPath(startNode, type, selectedExitId);
                    }
                }

                ItemResponse simulatedItem;
                if ((rawItem.isMovementPaused() || rawItem.isFlowPaused()
                        || (type == PositionType.CONVEYOR && liveConveyorRepository.isFlowStopped(positionId)))
                        && type == PositionType.CONVEYOR) {
                    simulatedItem = createItemResponse(id, positionId, null, entryTime,
                            Math.min(1.0, Math.max(0.0, accDist / 100.0)));
                } else if (stagedDistances.containsKey(id)) {
                    ConveyorResponse staging = topology.conveyorMap().get(positionId);
                    simulatedItem = createItemResponse(id, positionId, null, entryTime,
                            stagedDistances.get(id) / staging.getLength());
                } else {
                    simulatedItem = calculateCurrentState(
                            id, positionId, type, entryTime, path, topology, now, accDist);
                }

                if (simulatedItem != null) {
                    simulatedItem.setActive(!rawItem.isMovementPaused());
                    simulatedItem.setName(rawItem.getName());
                    simulatedItem.setPriority(itemPriorities.get(id));
                    simulatedItem.setProperties(itemPropertiesMap.getOrDefault(id, new HashMap<>()));
                    simulatedItem.setDestinations(destinations);
                    simulatedItem.setSelectedExitId(selectedExitId);
                    simulatedItem.setRoutingStatus(routingStatus);
                    simulatedItem.setRoutingStatusUpdatedAt(routingStatusUpdatedAt);
                    simulatedItem.setPath(path);
                    applyRushPriority(simulatedItem, now);
                    simulatedItem.setPlannedPositionId(rawItem.getPlannedPositionId());
                    simulatedItem.setPlannedPositionType(rawItem.getPlannedPositionType());
                    simulatedItem.setPlannedTransitionTimestamp(rawItem.getPlannedTransitionTimestamp());
                    simulatedItem.setStagingOrder(stagingOrders.get(id));
                    simulatedItem.setFlowPaused(rawItem.isFlowPaused());
                    simulatedItem.setMovementCheckTimestamp(rawItem.getMovementCheckTimestamp());
                    activeItems.add(simulatedItem);
                } else {
                    if (includeFinished) {
                        ItemResponse finished = new ItemResponse();
                        finished.setId(id);
                        finished.setName(rawItem.getName());
                        finished.setPriority(itemPriorities.get(id));
                        finished.setActive(false);
                        finished.setProgress(1.0);
                        finished.setCurrentEdgeId(positionId);
                        finished.setProperties(itemPropertiesMap.getOrDefault(id, new HashMap<>()));
                        finished.setEffectivePriority(finished.getPriority());
                        finished.setRushActive(false);
                        finished.setRoutingStatus(RoutingStatus.COMPLETED);
                        finished.setRoutingStatusUpdatedAt(now);
                        activeItems.add(finished);
                    }
                    if (shouldCleanup && simulationId == null) {
                        itemsToRemove.add(id);
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to process live item state for item {}", id, e);
                if (shouldCleanup && simulationId == null)
                    itemsToRemove.add(id);
            }
        }

        if (shouldCleanup && !itemsToRemove.isEmpty() && simulationId == null) {
            logger.info("Lazy Cleanup: Removing {} finished items from Redis", itemsToRemove.size());
            redisRepository.deleteItems(itemsToRemove);
        }
        return activeItems;
    }

    private Map<String, Double> calculateStagedDistances(List<RedisLiveItem> items, GraphService.Topology topology, Instant now,
            Map<String, Integer> stagingOrders) {
        Map<String, List<RedisLiveItem>> byConveyor = items.stream()
                .filter(item -> item.getType() == PositionType.CONVEYOR)
                .filter(item -> item.getPositionId() != null && item.getEntryTime() != null)
                .filter(item -> {
                    ConveyorResponse conveyor = topology.conveyorMap().get(item.getPositionId());
                    return conveyor != null && conveyor.getType() == ConveyorType.STAGING;
                })
                .collect(Collectors.groupingBy(RedisLiveItem::getPositionId));
        Map<String, Double> result = new HashMap<>();
        byConveyor.forEach((conveyorId, stagedItems) -> {
            ConveyorResponse conveyor = topology.conveyorMap().get(conveyorId);
            double length = Math.max(0.0, Objects.requireNonNullElse(conveyor.getLength(), 0.0));
            double speed = Math.max(0.0, Objects.requireNonNullElse(conveyor.getSpeed(), 0.0));
            double spacing = Math.max(0.0, Objects.requireNonNullElse(conveyor.getMinDistance(), 0.1));
            Map<String, RedisLiveItem> byId = stagedItems.stream()
                    .collect(Collectors.toMap(RedisLiveItem::getId, item -> item));
            List<RedisLiveItem> orderedItems = liveConveyorRepository.getItemsOrderedByDistance(conveyorId).stream()
                    .map(byId::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(ArrayList::new));
            if (orderedItems.size() != stagedItems.size()) {
                stagedItems.stream().filter(item -> !orderedItems.contains(item))
                        .sorted(Comparator.comparing(RedisLiveItem::getEntryTime).thenComparing(RedisLiveItem::getId))
                        .forEach(orderedItems::add);
            }
            for (int index = 0; index < orderedItems.size(); index++) {
                RedisLiveItem item = orderedItems.get(index);
                long elapsedMillis = item.isMovementPaused()
                        ? 0L
                        : Math.max(0L, Duration.between(item.getEntryTime(), now).toMillis());
                double storedDistance = length * Math.min(100.0, Math.max(0.0, item.getAccumulatedDistance())) / 100.0;
                double naturalDistance = storedDistance
                        + elapsedMillis / 1000.0 * speed;
                double slot = Math.max(0.0, length - index * spacing);
                result.put(item.getId(), Math.min(naturalDistance, slot));
                stagingOrders.put(item.getId(), index);
            }
        });
        return result;
    }

    /** Replays movement from its last checkpoint at the requested domain time. */
    private ItemResponse calculateCurrentState(
            String itemId, String startId, PositionType startType, Instant lastUpdate,
            List<String> path, GraphService.Topology topo, Instant now, Double accDist) {
        Duration timeElapsed = Duration.between(lastUpdate, now);
        if (timeElapsed.isNegative())
            timeElapsed = Duration.ZERO;

        ConveyorResponse currentEdge = null;
        String lastNodeId = null;

        // --- 1. DETERMINE INITIAL STATE ---

        if (startType == PositionType.CONVEYOR && topo.conveyorMap().containsKey(startId)) {
            // CASE A: Started on an Edge
            currentEdge = topo.conveyorMap().get(startId);
            double speed = Boolean.TRUE.equals(currentEdge.getActive()) && currentEdge.getSpeed() != null
                    ? Math.max(0.0, currentEdge.getSpeed()) : 0.0;
            double length = currentEdge.getLength() != null ? currentEdge.getLength() : 0.0;
            double progress = Math.min(1.0, Math.max(0.0, accDist / 100.0));
            if (length > 0) progress = Math.min(1.0,
                    progress + timeElapsed.toMillis() / 1000.0 * speed / length);
            return createItemResponse(itemId, currentEdge.getId(), null, now, progress);
        } else if (startType == PositionType.LOCATION && topo.nodeMap().containsKey(startId)) {
            // CASE B: Started on a Node
            lastNodeId = startId;
            Duration processingDuration = processingDuration(topo.nodeMap().get(startId));
            if (!processingDuration.isZero()) {
                if (timeElapsed.compareTo(processingDuration) < 0) {
                    return createItemResponse(itemId, null, startId, lastUpdate, 1.0);
                }
                timeElapsed = timeElapsed.minus(processingDuration);
            }
            currentEdge = findNextEdge(startId, topo.outgoingEdgesMap(), path);

            if (currentEdge == null) {
                boolean terminalLocation = !topo.outgoingEdgesMap().containsKey(startId);
                return createItemResponse(itemId, null, startId, lastUpdate,
                        terminalLocation ? 1.0 : 0.0);
            }
        } else {
            return null;
        }

        // --- 2. TRAVERSE GRAPH ---
        while (currentEdge != null) {
            double speed = currentEdge.getSpeed() != null ? currentEdge.getSpeed() : 0.0;
            double length = currentEdge.getLength() != null ? currentEdge.getLength() : 1.0;

            double startProgress = currentEdge.getId().equals(startId) && startType == PositionType.CONVEYOR
                    ? Math.min(100.0, Math.max(0.0, accDist))
                    : 0.0;

            if (!Boolean.TRUE.equals(currentEdge.getActive()) || speed <= 0) {
                return createItemResponse(itemId, currentEdge.getId(), null, lastUpdate, startProgress / 100.0);
            }

            long traversalTimeMillis = (long) ((length * (1.0 - startProgress / 100.0) / speed) * 1000);
            Duration traversalDuration = Duration.ofMillis(traversalTimeMillis);

            // CHECK: Is item still on this edge?
            if (timeElapsed.compareTo(traversalDuration) < 0) {
                double progressDelta = length > 0
                        ? (timeElapsed.toMillis() / 1000.0) * speed / length * 100.0
                        : 0.0;
                double progress = Math.min(100.0, startProgress + progressDelta);
                Instant currentEdgeEntryTime = now.minusMillis(
                        (long) ((progress / 100.0 * length / speed) * 1000));
                return createItemResponse(itemId, currentEdge.getId(), null, currentEdgeEntryTime, progress / 100.0);
            }

            // NO: Item finished this edge.
            timeElapsed = timeElapsed.minus(traversalDuration);

            // We have arrived at the target node
            String arrivalNodeId = currentEdge.getTargetId();

            lastNodeId = arrivalNodeId;
            Duration processingDuration = processingDuration(topo.nodeMap().get(arrivalNodeId));
            if (!processingDuration.isZero()) {
                Instant arrivalTime = now.minus(timeElapsed);
                if (timeElapsed.compareTo(processingDuration) < 0) {
                    return createItemResponse(itemId, null, arrivalNodeId, arrivalTime, 1.0);
                }
                timeElapsed = timeElapsed.minus(processingDuration);
            }
            currentEdge = findNextEdge(arrivalNodeId, topo.outgoingEdgesMap(), path);
        }

        return createItemResponse(itemId, null, lastNodeId, lastUpdate, 1.0);
    }

    private Duration processingDuration(LocationResponse location) {
        long delayMillis = location == null
                ? 0L
                : processingDelayMillis(location);
        return delayMillis <= 0L ? Duration.ZERO : Duration.ofMillis(delayMillis);
    }

    private long processingDelayMillis(LocationResponse location) {
        Long delay = location.getTimeToProcessMs();
        return location.getType() == flunav.types.LocationType.TIMED_NODE && delay != null && delay > 0L
                ? delay
                : 0L;
    }

    private ConveyorResponse findNextEdge(
            String currentNodeId,
            Map<String, List<ConveyorResponse>> outgoing,
            List<String> path) {

        List<ConveyorResponse> edges = outgoing.get(currentNodeId);
        if (edges == null || edges.isEmpty())
            return null;

        // 1. Path Priority
        if (path != null && !path.isEmpty()) {
            int currentIndex = path.indexOf(currentNodeId);
            if (currentIndex >= 0 && currentIndex < path.size() - 1) {
                String nextTargetNodeId = path.get(currentIndex + 1);
                Optional<ConveyorResponse> match = edges.stream()
                        .filter(e -> e.getTargetId().equals(nextTargetNodeId))
                        .findFirst();
                if (match.isPresent())
                    return match.get();
            }
        }

        // 2. Main Path Priority
        Optional<ConveyorResponse> main = edges.stream()
                .filter(e -> Boolean.TRUE.equals(e.getMainPath()))
                .findFirst();
        if (main.isPresent())
            return main.get();

        // 3. Fallback
        return edges.size() > 1 ? null : edges.get(0);
    }

    private ItemResponse createItemResponse(String id, String edgeId, String locId, Instant entry, Double progress) {
        ItemResponse item = new ItemResponse();
        item.setId(id);
        item.setCurrentEdgeId(edgeId);
        item.setLocationId(edgeId == null ? locId : null);
        item.setEntryTimestamp(entry);
        item.setProgress(Math.min(1.0, Math.max(0.0, progress)));
        item.setActive(true);
        return item;
    }

    private void applyRushPriority(ItemResponse response, Instant timestamp) {
        DestinationMappingService.RushPriority rush = destinationMappingService.evaluateRush(
                RuleFieldProjection.itemRootFields(response), response.getProperties(), response.getDestinations(), response.getPriority(),
                timestamp);
        response.setEffectivePriority(rush.effectivePriority());
        response.setRushActive(rush.rushActive());
    }

    private RoutingStatus effectiveRoutingStatus(RoutingStatus status, String selectedExitId) {
        if (status != null) {
            return status;
        }
        return selectedExitId == null ? RoutingStatus.UNROUTED : RoutingStatus.ASSIGNED;
    }
}
