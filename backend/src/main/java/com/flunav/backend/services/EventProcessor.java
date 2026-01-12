package com.flunav.backend.services;

import com.flunav.backend.domain.Conveyor;
import com.flunav.backend.models.UpdateModel;
import com.flunav.backend.models.input.ItemInput;
import com.flunav.backend.models.input.LocationInput;
import com.flunav.backend.models.response.ConveyorResponse;
import com.flunav.backend.models.response.ItemResponse;
import com.flunav.backend.repositories.LiveItemRepository;
import com.orientechnologies.orient.core.exception.OConcurrentModificationException;
import flunav.context.UserContextHolder;
import flunav.events.*;
import flunav.types.LocationType;
import jakarta.annotation.PreDestroy;

import org.modelmapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import com.flunav.backend.context.DatabaseContextHolder;

@Service
public class EventProcessor {
    private static final Logger logger = LoggerFactory.getLogger(EventProcessor.class);

    // --- Servizi Dipendenti (Invariati) ---
    private final ClickHouseService clickHouseService;
    private final ItemService itemService;
    private final LocationService locationService;
    private final ConveyorService conveyorService;
    private final WebSocketService webSocketService;
    private final PathfindingService pathfindingService;
    private final LiveItemRepository liveItemRepository;
    private final DisplayRulesService displayRulesService;

    ModelMapper modelMapper = new ModelMapper();

    // --- NUOVA LOGICA: Gestori di Esecuzione ---

    // 1. Un unico pool di thread principale, che useremo per tutte le operazioni.
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    // 2. La mappa che tiene traccia dei "lavori in corso" per ogni entityId.
    // Il valore è il Future dell'ULTIMO task in coda per quell'ID.
    private final ConcurrentMap<String, CompletableFuture<Void>> processingFutures = new ConcurrentHashMap<>();

    public EventProcessor(
            ClickHouseService clickHouseService,
            ItemService itemService,
            LocationService locationService,
            ConveyorService conveyorService,
            WebSocketService webSocketService,
            PathfindingService pathfindingService,
            LiveItemRepository liveItemRepository,
            DisplayRulesService displayRulesService) {
        this.clickHouseService = clickHouseService;
        this.itemService = itemService;
        this.locationService = locationService;
        this.conveyorService = conveyorService;
        this.webSocketService = webSocketService;
        this.pathfindingService = pathfindingService;
        this.liveItemRepository = liveItemRepository;
        this.displayRulesService = displayRulesService;
    }

    /**
     * Metodo pubblico principale, ora con logica di chaining per l'ordinamento.
     */
    public CompletableFuture<Map<String, Object>> process(DomainEvent event, boolean shouldBroadcast) {
        final String entityId = (event instanceof EntityEvent e) ? e.getEntityId() : null;

        // Se non c'è un ID, esegui subito in modo asincrono usando il nostro helper
        // standard.
        if (entityId == null) {
            return executeOn(event, shouldBroadcast, executor);
        }

        // Questo Future rappresenta il risultato del *nostro* task specifico.
        CompletableFuture<Map<String, Object>> taskResultFuture = new CompletableFuture<>();

        // Aggiorna atomicamente la mappa per concatenare il nostro task.
        processingFutures.compute(entityId, (id, previousTaskCompletion) -> {

            // Definiamo il nostro lavoro come un Supplier<CompletableFuture>
            Supplier<CompletableFuture<Map<String, Object>>> workSupplier = () -> executeOn(event, shouldBroadcast,
                    executor);

            if (previousTaskCompletion == null || previousTaskCompletion.isDone()) {
                // Nessun task precedente, esegui subito.
                workSupplier.get()
                        .whenComplete((result, error) -> {
                            if (error != null)
                                taskResultFuture.completeExceptionally(error);
                            else
                                taskResultFuture.complete(result);
                        });
            } else {
                // C'è un task precedente, agganciati alla sua fine.
                previousTaskCompletion.whenComplete((ignored, throwable) -> {
                    workSupplier.get()
                            .whenComplete((result, error) -> {
                                if (error != null)
                                    taskResultFuture.completeExceptionally(error);
                                else
                                    taskResultFuture.complete(result);
                            });
                });
            }

            // Il nuovo "ultimo task" della catena è il nostro.
            return taskResultFuture.thenApply(v -> null); // Converti a CompletableFuture<Void>
        });

        return taskResultFuture;
    }

    /**
     * Metodo helper privato per eseguire la logica di business su un executor
     * specifico.
     * È il "worker" che viene chiamato dalla logica di 'process'.
     * La gestione dei ThreadLocal è centralizzata qui.
     */
    private CompletableFuture<Map<String, Object>> executeOn(DomainEvent event, boolean shouldBroadcast,
            Executor executor) {
        // CAPTURE CONTEXT FROM CALLER THREAD
        final String currentSimId = DatabaseContextHolder.getSimulationId();
        final String currentSenderId = UserContextHolder.getSenderId();

        return CompletableFuture.supplyAsync(
                () -> executeBusinessLogic(event, shouldBroadcast, currentSimId, currentSenderId), executor);
    }

    /**
     * Contiene la logica di business effettiva.
     * Centralizza la gestione dei ThreadLocal e le chiamate ai servizi.
     */
    private Map<String, Object> executeBusinessLogic(DomainEvent event, boolean shouldBroadcast, String simulationId,
            String senderId) {
        try {
            if (simulationId != null)
                DatabaseContextHolder.enterSimulationContext(simulationId);
            if (senderId != null)
                UserContextHolder.setSenderId(senderId);

            if (simulationId == null) {
                clickHouseService.saveEventAsync(event);
            }

            Map<String, Object> resultMap = processEvent(event, shouldBroadcast);
            logger.info("Successfully processed event: {}", event.getEventType());
            return resultMap;

        } catch (Exception e) {
            logger.error("Error processing event: {}", event.getEventType(), e);
            throw new CompletionException(e);
        } finally {
            DatabaseContextHolder.clearSimulation();
            UserContextHolder.clear();
        }
    }

    private <T> T executeWithRetry(Supplier<T> operation) {
        final int MAX_RETRIES = 5;
        int attempt = 0;
        while (true) {
            try {
                return operation.get();
            } catch (OConcurrentModificationException | java.util.NoSuchElementException e) {
                attempt++;
                if (attempt >= MAX_RETRIES)
                    throw e;
                try {
                    Thread.sleep(50 * (long) Math.pow(2, attempt - 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Retry interrupted", ie);
                }
            }
        }
    }

    // --- Metodi di processamento interni (Invariati) ---

    public Map<String, Object> processEvent(DomainEvent event) {
        return processEvent(event, true);
    }

    public Map<String, Object> processEventWithoutBroadcast(DomainEvent event) {
        return processEvent(event, false);
    }

    private Map<String, Object> processEvent(DomainEvent event, boolean shouldBroadcast) {
        UserContextHolder.setSenderId(event.getSenderId());
        return this.<Map<String, Object>>executeWithRetry(() -> {
            return switch (event) {
                // --- ITEM EVENTS ---

                case ItemCreatedEvent e -> {
                    var item = new ItemInput(e);
                    itemService.createItem(item);
                    if (shouldBroadcast) {
                        ItemResponse response = modelMapper.map(item, ItemResponse.class);
                        response.setCustomColor(this.displayRulesService.applyDisplayRules(item.getProperties(),
                                this.displayRulesService.getDisplayRules()));
                        webSocketService.broadcastItemCreated(response, e.getTimestamp());
                    }
                    yield Map.of("status", "CREATED", "itemId", e.getEntityId());
                }

                case ItemPositionChangedEvent e -> {
                    var location = locationService.getLocationById(e.getLocationId());
                    var positionType = locationService.getPositionType(e.getLocationId());

                    if (location.getType() == LocationType.CHUTE) {
                        liveItemRepository.deleteItem(e.getEntityId());

                        // Tell frontend to remove it visually
                        if (shouldBroadcast) {
                            webSocketService.broadcastItemDeleted(e.getEntityId(), e.getTimestamp());
                        }
                    } else {

                        itemService.updateItemPosition(e.getEntityId(), e.getLocationId(), positionType,
                                e.getTimestamp(),
                                e.getProgress(), null);

                        if (shouldBroadcast) {
                            webSocketService.broadcastPositionUpdate(e.getEntityId(), e.getLocationId(),
                                    e.getTimestamp(),
                                    positionType, e.getProgress());
                        }
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ItemPositionDeletedEvent e -> {
                    itemService.updateItemPosition(e.getEntityId(), null, null, Instant.now(), null, null);
                    if (shouldBroadcast) {
                        webSocketService.broadcastPositionLost(e.getEntityId(), e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ItemRenamedEvent e -> {
                    var item = itemService.getItemById(e.getEntityId());
                    item.setName(e.getNewName());
                    itemService.updateItem(new UpdateModel(item.getId(), Map.of("name", e.getNewName())));

                    if (shouldBroadcast) {
                        webSocketService.broadcastItemUpdated(
                                new UpdateModel(item.getId(), Map.of("name", e.getNewName())), e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ItemSpeedChangedEvent e -> {
                    var item = itemService.getItemById(e.getEntityId());
                    itemService.updateItem(new UpdateModel(item.getId(), Map.of("speed", e.getSpeed())));

                    if (shouldBroadcast) {
                        webSocketService.broadcastItemUpdated(
                                new UpdateModel(item.getId(), Map.of("speed", e.getSpeed())), e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ItemDeactivatedEvent e -> {
                    var item = itemService.getItemById(e.getEntityId());
                    itemService.updateItem(new UpdateModel(item.getId(), Map.of("active", false)));
                    if (shouldBroadcast) {
                        webSocketService.broadcastItemUpdated(
                                new UpdateModel(item.getId(), Map.of("active", false)), e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ItemActivatedEvent e -> {
                    var item = itemService.getItemById(e.getEntityId());
                    itemService.updateItem(new UpdateModel(item.getId(), Map.of("active", true)));
                    if (shouldBroadcast) {
                        webSocketService.broadcastItemUpdated(
                                new UpdateModel(item.getId(), Map.of("active", true)), e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ItemPropertiesUpdatedEvent e -> {
                    var item = itemService.getItemById(e.getEntityId());
                    item.updateProperties(e);
                    Map<String, Object> map = new HashMap<>();
                    map.put("properties", item.getProperties());
                    var updateModel = new UpdateModel(item.getId(), map);
                    itemService.updateItem(updateModel);
                    if (shouldBroadcast) {
                        map.put("customColor", this.displayRulesService.applyDisplayRules(item.getProperties(),
                                this.displayRulesService.getDisplayRules()));
                        webSocketService.broadcastItemUpdated(updateModel, e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ItemDeletedEvent e -> {
                    itemService.deleteItem(e.getEntityId());
                    if (shouldBroadcast) {
                        webSocketService.broadcastItemDeleted(e.getEntityId(), e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ItemDestinationEvent e -> {
                    var item = itemService.getItemById(e.getEntityId());

                    List<String> calculatedPath = pathfindingService.calculateShortestPath(
                            item.getPositionId(),
                            item.getPositionType(),
                            e.getLocationId());

                    item.setDestinationId(e.getLocationId());
                    item.setPath(calculatedPath);

                    itemService.fullUpdateItem(item);

                    if (shouldBroadcast) {
                        webSocketService.broadcastItemUpdated(new UpdateModel(item.getId(),
                                Map.of("destination", e.getLocationId(), "path", calculatedPath)), e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                // --- LOCATION EVENTS (Nodes) ---

                case LocationCreatedEvent e -> {
                    var location = new LocationInput(e);
                    locationService.createLocation(location);
                    if (shouldBroadcast) {
                        webSocketService.broadcastLocationCreated(location, e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case LocationPropertiesUpdatedEvent e -> {
                    var location = locationService.getLocationById(e.getEntityId());
                    location.updateProperties(e);
                    Map<String, Object> map = new HashMap<>();
                    map.put("properties", location.getProperties());
                    var updateModel = new UpdateModel(location.getId(), map);
                    locationService.updateLocation(updateModel);
                    if (shouldBroadcast) {
                        map.put("customColor", this.displayRulesService.applyDisplayRules(location.getProperties(),
                                this.displayRulesService.getDisplayRules()));
                        webSocketService.broadcastLocationPropertiesUpdated(updateModel, e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case LocationCoordinatesChangedEvent e -> {
                    var location = locationService.getLocationById(e.getEntityId());
                    location.updateCoordinates(e.getLatitude(), e.getLongitude());
                    var updateModel = new UpdateModel(location.getId(),
                            Map.of("latitude", location.getLatitude(), "longitude", location.getLongitude()));
                    locationService.updateLocation(updateModel);
                    if (shouldBroadcast) {
                        webSocketService.broadcastLocationPropertiesUpdated(updateModel, e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case LocationCapacityChangedEvent e -> {
                    var location = locationService.getLocationById(e.getEntityId());
                    location.updateCapacity(e.getCapacity());
                    var updateModel = new UpdateModel(location.getId(), Map.of("capacity", location.getCapacity()));
                    locationService.updateLocation(updateModel);
                    if (shouldBroadcast) {
                        webSocketService.broadcastLocationPropertiesUpdated(updateModel, e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case LocationDeletedEvent e -> {
                    locationService.deleteLocation(e.getEntityId());
                    if (shouldBroadcast) {
                        webSocketService.broadcastLocationDeleted(e.getEntityId(), e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                // --- CONNECTION EVENTS (Conveyors/Edges) ---

                case ConnectionCreatedEvent e -> {
                    conveyorService.createConveyor(
                            e.getConnectionId(),
                            e.getSourceId(),
                            e.getTargetId(),
                            e.getName(),
                            e.getLength(),
                            e.getSpeed(),
                            e.getIsMainPath(),
                            e.getIsActive());
                    if (shouldBroadcast) {
                        String customColor = this.displayRulesService.applyDisplayRules(e.getProperties(),
                                this.displayRulesService.getDisplayRules());
                        webSocketService.broadcastConnectionCreated(new ConveyorResponse(e.getConnectionId(),
                                e.getSourceId(), e.getTargetId(), e.getName(), e.getLength(), e.getSpeed(), e.getType(),
                                e.getIsActive(), e.getIsMainPath(), e.getCapacity(),
                                e.getProperties(), customColor), e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ConnectionSpeedChangedEvent e -> {
                    var conveyor = conveyorService.getConveyorById(e.getEntityId());
                    checkpointItems(e.getEntityId(), conveyor.getSpeed(), e.getTimestamp());
                    conveyor.setSpeed(e.getSpeed());
                    conveyorService.updateConveyor(conveyor);
                    if (shouldBroadcast) {
                        webSocketService.broadcastConnectionUpdated(
                                new UpdateModel(conveyor.getId(), Map.of("speed", e.getSpeed())), e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ConnectionLengthChangedEvent e -> {
                    var conveyor = conveyorService.getConveyorById(e.getEntityId());
                    conveyor.setLength(e.getLength());
                    conveyorService.updateConveyor(conveyor);

                    if (shouldBroadcast) {
                        webSocketService.broadcastConnectionUpdated(
                                new UpdateModel(conveyor.getId(), Map.of("length", e.getLength())), e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ConnectionPropertiesUpdatedEvent e -> {
                    var conveyor = conveyorService.getConveyorById(e.getEntityId());
                    conveyor.setProperties(e.getUpdatedProperties());
                    conveyorService.updateConveyor(conveyor);

                    if (shouldBroadcast) {
                        webSocketService.broadcastConnectionUpdated(
                                new UpdateModel(conveyor.getId(),
                                        Map.of("properties", e.getUpdatedProperties(), "customColor",
                                                this.displayRulesService.applyDisplayRules(conveyor.getProperties(),
                                                        this.displayRulesService.getDisplayRules()))),
                                e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case LocationAddToMainPath e -> {
                    var conveyor = conveyorService.getConveyorById(e.getEntityId());
                    conveyor.setMainPath(true);
                    conveyorService.updateConveyor(conveyor);

                    if (shouldBroadcast) {
                        webSocketService.broadcastConnectionUpdated(
                                new UpdateModel(conveyor.getId(), Map.of("isMainPath", true)), e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ConnectionRemoveFromMainPath e -> {
                    var conveyor = conveyorService.getConveyorById(e.getEntityId());
                    conveyor.setMainPath(false);
                    conveyorService.updateConveyor(conveyor);

                    if (shouldBroadcast) {
                        webSocketService.broadcastConnectionUpdated(
                                new UpdateModel(conveyor.getId(), Map.of("isMainPath", false)), e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ConnectionDeletedEvent e -> {
                    conveyorService.deleteConveyor(e.getSourceLocationId(), e.getTargetLocationId());
                    if (shouldBroadcast) {
                        webSocketService.broadcastConnectionDeleted(e.getSourceLocationId(), e.getTargetLocationId(),
                                e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                default -> {
                    logger.warn("Unknown event type: {}", event.getClass().getSimpleName());
                    yield Map.of("status", "UNKNOWN_EVENT");
                }
            };
        });
    }

    /**
     * Checkpoints items on a conveyor when speed changes.
     * We save the distance traveled so far and reset the timer to 'now'.
     */
    private void checkpointItems(String edgeId, double oldSpeed, Instant timestamp) {
        List<Map<String, Object>> allItems = liveItemRepository.getAllActiveItems();
        long now = timestamp.toEpochMilli();
        Instant nowInstant = timestamp;

        for (Map<String, Object> itemData : allItems) {
            String currentEdgeId = (String) itemData.get("edgeId");

            if (edgeId.equals(currentEdgeId)) {
                String itemId = (String) itemData.get("id");
                Long lastUpdateTime = (Long) itemData.get("entryTimestamp");
                Double storedDistance = (Double) itemData.get("accumulatedDistance");
                if (storedDistance == null)
                    storedDistance = 0.0;

                if (lastUpdateTime != null) {
                    long timeElapsed = now - lastUpdateTime;
                    double distanceTraveledSinceLastUpdate = (timeElapsed / 1000.0) * oldSpeed;

                    double totalDistance = storedDistance + distanceTraveledSinceLastUpdate;

                    liveItemRepository.checkpointPhysics(itemId, nowInstant, totalDistance);
                }
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        logger.info("Shutting down EventProcessor executor...");
        shutdownExecutor(executor, "Main Executor");
        logger.info("Executor has been shut down.");
    }

    private void shutdownExecutor(ExecutorService exec, String name) {
        exec.shutdown();
        try {
            if (!exec.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("{} did not terminate in 5 seconds. Forcing shutdown.", name);
                exec.shutdownNow();
            }
        } catch (InterruptedException e) {
            logger.error("Shutdown was interrupted for {}.", name, e);
            exec.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}