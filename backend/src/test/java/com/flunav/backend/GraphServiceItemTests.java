package com.flunav.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.input.ItemInput;
import com.flunav.backend.models.input.LocationInput;
import com.flunav.backend.models.response.ItemResponse;
import com.flunav.backend.repositories.LiveConveyorRepository;
import com.flunav.backend.repositories.LiveItemRepository;
import com.flunav.backend.repositories.LiveLocationRepository;
import com.flunav.backend.services.ConveyorService;
import com.flunav.backend.services.DestinationMappingService;
import com.flunav.backend.services.DestinationExitMappingService;
import com.flunav.backend.services.EventProcessor;
import com.flunav.backend.services.GraphService;
import com.flunav.backend.services.ItemMovementProcessor;
import com.flunav.backend.services.ItemService;
import com.flunav.backend.services.LiveSystemScheduler;
import com.flunav.backend.services.OrientDBService;
import com.flunav.backend.services.OperationalAnalyticsService;
import com.flunav.backend.services.PathfindingService;
import com.flunav.backend.services.PathAssignmentPublisher;
import com.flunav.backend.services.RoutingDecisionService;
import com.flunav.backend.services.SimulationService;
import com.flunav.backend.services.StaleItemCleanupService;
import com.flunav.backend.services.TimeService;
import com.flunav.backend.services.TopologyProvider;
import com.flunav.backend.services.WebSocketService;
import flunav.events.DestinationMappingRecord;
import flunav.events.DestinationExitMappingRecord;
import flunav.events.ChuteEmptyEvent;
import flunav.events.ConnectionActivatedEvent;
import flunav.events.ConnectionDeactivatedEvent;
import flunav.events.ConnectionDeletedEvent;
import flunav.events.ConnectionLengthChangedEvent;
import flunav.events.ConnectionTypeChangedEvent;
import flunav.events.ItemCreatedEvent;
import flunav.events.ItemDestinationEvent;
import flunav.events.ItemPositionChangedEvent;
import flunav.events.ItemPriorityUpdatedEvent;
import flunav.events.ItemRoutingDecisionRequestedEvent;
import flunav.events.MapDestinationsEvent;
import flunav.events.MapDestinationExitsEvent;
import flunav.events.PathTraversedEvent;
import flunav.events.ReleaseStagingConveyorEvent;
import flunav.messages.ItemPathAssignmentMessage;
import flunav.types.DataType;
import flunav.types.ConveyorType;
import flunav.types.LocationType;
import flunav.types.OperatorType;
import flunav.types.PositionType;
import flunav.types.RoutingStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "springwolf.enabled=false",
        "rabbitmq.routing-key.item-events=1",
        "state-recovery.enabled=false",
        "graph-snapshot.enabled=false"
})
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class GraphServiceItemTests extends BaseIntegrationTest {

    private static final String SIMULATION_ID = "graph-service-test-sim";

    private final GraphService graphService;
    private final ItemService itemService;
    private final LiveConveyorRepository liveConveyorRepository;
    private final LiveItemRepository liveItemRepository;
    private final LiveLocationRepository liveLocationRepository;
    private final StaleItemCleanupService staleItemCleanupService;
    private final EventProcessor eventProcessor;
    private final ItemMovementProcessor itemMovementProcessor;
    private final RoutingDecisionService routingDecisionService;
    private final DestinationMappingService destinationMappingService;
    private final DestinationExitMappingService destinationExitMappingService;
    private final TopologyProvider topologyProvider;
    private final SimulationService simulationService;
    private final LiveSystemScheduler liveSystemScheduler;
    private final TimeService timeService;
    private final PathAssignmentPublisher pathAssignmentPublisher;
    private final WebSocketService webSocketService;
    private final PathfindingService pathfindingService;
    private final com.flunav.backend.services.LocationService locationService;
    private final ConveyorService conveyorService;
    private final OrientDBService orientDBService;
    private final StringRedisTemplate redisTemplate;
    private final AmqpTemplate amqpTemplate;
    private final AmqpAdmin amqpAdmin;
    private final OperationalAnalyticsService operationalAnalyticsService;

    GraphServiceItemTests(
            GraphService graphService,
            ItemService itemService,
            LiveConveyorRepository liveConveyorRepository,
            LiveItemRepository liveItemRepository,
            LiveLocationRepository liveLocationRepository,
            StaleItemCleanupService staleItemCleanupService,
            EventProcessor eventProcessor,
            ItemMovementProcessor itemMovementProcessor,
            RoutingDecisionService routingDecisionService,
            DestinationMappingService destinationMappingService,
            DestinationExitMappingService destinationExitMappingService,
            TopologyProvider topologyProvider,
            SimulationService simulationService,
            LiveSystemScheduler liveSystemScheduler,
            TimeService timeService,
            PathAssignmentPublisher pathAssignmentPublisher,
            WebSocketService webSocketService,
            PathfindingService pathfindingService,
            com.flunav.backend.services.LocationService locationService,
            ConveyorService conveyorService,
            OrientDBService orientDBService,
            StringRedisTemplate redisTemplate,
            AmqpTemplate amqpTemplate,
            AmqpAdmin amqpAdmin,
            OperationalAnalyticsService operationalAnalyticsService) {
        this.graphService = graphService;
        this.itemService = itemService;
        this.liveConveyorRepository = liveConveyorRepository;
        this.liveItemRepository = liveItemRepository;
        this.liveLocationRepository = liveLocationRepository;
        this.staleItemCleanupService = staleItemCleanupService;
        this.eventProcessor = eventProcessor;
        this.itemMovementProcessor = itemMovementProcessor;
        this.routingDecisionService = routingDecisionService;
        this.destinationMappingService = destinationMappingService;
        this.destinationExitMappingService = destinationExitMappingService;
        this.topologyProvider = topologyProvider;
        this.simulationService = simulationService;
        this.liveSystemScheduler = liveSystemScheduler;
        this.timeService = timeService;
        this.pathAssignmentPublisher = pathAssignmentPublisher;
        this.webSocketService = webSocketService;
        this.pathfindingService = pathfindingService;
        this.locationService = locationService;
        this.conveyorService = conveyorService;
        this.orientDBService = orientDBService;
        this.redisTemplate = redisTemplate;
        this.amqpTemplate = amqpTemplate;
        this.amqpAdmin = amqpAdmin;
        this.operationalAnalyticsService = operationalAnalyticsService;
    }

    @BeforeEach
    void setup() {
        resetState();
        drainCommandsQueue();
        drainPathAssignmentsQueue();
    }

    @AfterEach
    void cleanup() {
        liveSystemScheduler.cancelAll();
        resetState();
        drainCommandsQueue();
        drainPathAssignmentsQueue();
    }

    @Test
    void cleanupExpiredLiveItemsOnStartupRemovesOnlyLiveItemsOlderThan24Hours() {
        Instant now = Instant.parse("2026-03-22T12:00:00Z");

        liveItemRepository.saveItemState("live-stale", "live-start", PositionType.LOCATION, now.minusSeconds(25 * 3600),
                0.0, "Live Stale", null, null, null);
        liveItemRepository.saveItemState("live-fresh", "live-start", PositionType.LOCATION, now.minusSeconds(23 * 3600),
                0.0, "Live Fresh", null, null, null);

        orientDBService.createInMemoryDatabase(SIMULATION_ID);
        try (var ignored = DatabaseContextHolder.enterSimulationContext(SIMULATION_ID)) {
            liveItemRepository.saveItemState("sim-stale", "sim-start", PositionType.LOCATION,
                    now.minusSeconds(30 * 3600),
                    0.0, "Simulation Stale", null, null, null);
        }

        staleItemCleanupService.cleanupExpiredLiveItemsOnStartup(now);

        assertNull(liveItemRepository.getItemState("live-stale", null));
        assertNotNull(liveItemRepository.getItemState("live-fresh", null));
        assertNotNull(liveItemRepository.getItemState("sim-stale", SIMULATION_ID));
    }

    @Test
    void hotStateKeysDoNotExpireIndependently() {
        Instant now = Instant.now();
        liveItemRepository.saveItemState("ttl-item", "ttl-location", PositionType.LOCATION, now,
                0.0, "TTL Item", null, null, null);
        liveLocationRepository.addItemToLocation("ttl-location", "ttl-item");
        liveConveyorRepository.addItemToConveyor("ttl-conveyor", "ttl-item", now);

        assertEquals(-1L, redisTemplate.getExpire("item:ttl-item", TimeUnit.SECONDS));
        assertEquals(-1L, redisTemplate.getExpire("active_items", TimeUnit.SECONDS));
        assertEquals(-1L, redisTemplate.getExpire("loc:ttl-location:items", TimeUnit.SECONDS));
        assertEquals(-1L, redisTemplate.getExpire("conv:ttl-conveyor:items", TimeUnit.SECONDS));
    }

    @Test
    void simulationEventWithSameEntityDoesNotWaitForBlockedLiveEvent() throws Exception {
        CountDownLatch barrierAcquired = new CountDownLatch(1);
        CountDownLatch releaseBarrier = new CountDownLatch(1);
        ExecutorService barrierExecutor = Executors.newSingleThreadExecutor();
        CompletableFuture<Map<String, Object>> liveFuture = null;
        try {
            var barrierFuture = barrierExecutor.submit(() -> eventProcessor.withLiveSnapshotBarrier(() -> {
                barrierAcquired.countDown();
                try {
                    if (!releaseBarrier.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release snapshot barrier");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Snapshot barrier interrupted", e);
                }
                return null;
            }));
            assertTrue(barrierAcquired.await(2, TimeUnit.SECONDS));

            Instant now = Instant.now();
            PathTraversedEvent liveEvent = new PathTraversedEvent(
                    "shared-entity", "live-a", PositionType.LOCATION,
                    "live-b", PositionType.LOCATION, List.of("live-a", "live-b"), now);
            liveFuture = eventProcessor.process(liveEvent, false);
            assertFalse(liveFuture.isDone());

            try (var ignored = DatabaseContextHolder.enterSimulationContext("context-ordering-sim")) {
                PathTraversedEvent simulationEvent = new PathTraversedEvent(
                        "shared-entity", "sim-a", PositionType.LOCATION,
                        "sim-b", PositionType.LOCATION, List.of("sim-a", "sim-b"), now);
                assertEquals("PROCESSED_SUCCESSFULLY",
                        eventProcessor.process(simulationEvent, false).get(2, TimeUnit.SECONDS).get("status"));
            }

            releaseBarrier.countDown();
            assertEquals("PROCESSED_SUCCESSFULLY", liveFuture.get(2, TimeUnit.SECONDS).get("status"));
            barrierFuture.get(2, TimeUnit.SECONDS);
        } finally {
            releaseBarrier.countDown();
            if (liveFuture != null) {
                liveFuture.cancel(true);
            }
            barrierExecutor.shutdownNow();
        }
    }

    @Test
    void duplicateItemCreateDoesNotApplyIncomingHotStateSideEffects() {
        Instant now = Instant.now();
        createLocation("duplicate-chute-a", "First Chute", LocationType.CHUTE, 10);
        createLocation("duplicate-chute-b", "Second Chute", LocationType.CHUTE, 10);

        Map<String, Object> first = eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                "duplicate-item", "First", 1.0, true, "duplicate-chute-a",
                PositionType.LOCATION, 0.0, Map.of(), now));
        Map<String, Object> duplicate = eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                "duplicate-item", "Second", 1.0, true, "duplicate-chute-b",
                PositionType.LOCATION, 0.0, Map.of(), now.plusSeconds(1)));

        assertEquals("CREATED", first.get("status"));
        assertEquals("IGNORED_DUPLICATE", duplicate.get("status"));
        assertEquals("duplicate-chute-a", liveItemRepository.getItemState("duplicate-item").getPositionId());
        assertEquals(Set.of("duplicate-item"), liveLocationRepository.getItemsAtLocation("duplicate-chute-a"));
        assertTrue(liveLocationRepository.getItemsAtLocation("duplicate-chute-b").isEmpty());
        assertEquals("First", itemService.getItemById("duplicate-item").getName());
    }

    @Test
    void deletingConnectionCancelsSchedulesAndRemovesOrphanedHotState() {
        Instant now = Instant.now();
        createLocation("delete-connection-start", "Start");
        createLocation("delete-connection-end", "End");
        conveyorService.createConveyor(
                "delete-connection-conveyor", "delete-connection-start", "delete-connection-end",
                "Delete Me", 10.0, 1.0, 0.0, false, true);
        liveItemRepository.saveItemState(
                "delete-connection-item", "delete-connection-conveyor", PositionType.CONVEYOR,
                now, 0.0, "Hot Item", null, null, null);
        liveConveyorRepository.addItemToConveyor(
                "delete-connection-conveyor", "delete-connection-item", now);
        liveSystemScheduler.scheduleInternalEvent(new ItemPositionChangedEvent(
                "delete-connection-item", "delete-connection-end", 100.0, now.plusSeconds(60)));

        eventProcessor.process(new ConnectionDeletedEvent(
                "delete-connection-start", "delete-connection-end"), false).join();

        assertNull(liveItemRepository.getItemState("delete-connection-item"));
        assertTrue(liveConveyorRepository.getItemsOrderedByDistance("delete-connection-conveyor").isEmpty());
        assertNull(liveSystemScheduler.getScheduledEvent("delete-connection-item"));
        assertThrows(RuntimeException.class,
                () -> topologyProvider.getConveyorById("delete-connection-conveyor"));
    }

    @Test
    void getGraphDataReturnsLiveItemStateMergedWithMetadata() {
        Instant now = Instant.parse("2026-03-22T12:00:00Z");

        createLocation("live-start", "Start");
        createLocation("live-end", "End");
        conveyorService.createConveyor("live-conveyor", "live-start", "live-end", "Live Conveyor", 10.0, 1.0, 0.0,
                false, true);
        createItem("live-item", "Live Item", "live-start", now.minusSeconds(5), Map.of("sku", "A-1"));

        List<ItemResponse> items = graphService.getGraphData(now, false, null, false).getItems();

        assertEquals(1, items.size());
        ItemResponse item = items.getFirst();
        assertEquals("live-item", item.getId());
        assertEquals("Live Item", item.getName());
        assertEquals("live-conveyor", item.getCurrentEdgeId());
        assertNull(item.getLocationId());
        assertEquals("A-1", item.getProperties().get("sku"));
        assertTrue(item.getProgress() > 0.45 && item.getProgress() < 0.55);
    }

    @Test
    void getGraphDataOnlyCleansInvalidItemsForLiveContext() {
        Instant now = Instant.parse("2026-03-22T12:00:00Z");

        liveItemRepository.saveItemState("live-invalid", "missing-live-location", PositionType.LOCATION,
                now.minusSeconds(30), 0.0, "Live Invalid", null, null, null);

        orientDBService.createInMemoryDatabase(SIMULATION_ID);
        try (var ignored = DatabaseContextHolder.enterSimulationContext(SIMULATION_ID)) {
            liveItemRepository.saveItemState("sim-invalid", "missing-sim-location", PositionType.LOCATION,
                    now.minusSeconds(30), 0.0, "Simulation Invalid", null, null, null);
        }

        List<ItemResponse> liveItems = graphService.getGraphData(now, true, null, false).getItems();
        List<ItemResponse> simulationItems = graphService.getGraphData(now, true, SIMULATION_ID, false).getItems();

        assertTrue(liveItems.isEmpty());
        assertTrue(simulationItems.isEmpty());
        assertNull(liveItemRepository.getItemState("live-invalid", null));
        assertNotNull(liveItemRepository.getItemState("sim-invalid", SIMULATION_ID));
    }

    @Test
    void itemCreatedWithoutDestinationUsesActiveDestinationMappingFallback() {
        Instant now = Instant.now();
        createLocation("mapped-start", "Start");
        createLocation("mapped-exit-a", "Exit A");
        conveyorService.createConveyor("mapped-conv-exit-a", "mapped-start", "mapped-exit-a", "Exit A Conveyor", 10.0, 1.0, 0.0,
                true, true);

        eventProcessor.processEventWithoutBroadcast(new MapDestinationsEvent("flight_number", List.of(
                new DestinationMappingRecord("123", List.of("mapped-exit-a"), now.minusSeconds(60),
                        now.plusSeconds(3600)))));
        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent("item-mapped", "Mapped Item", 1.0, true,
                "mapped-start", PositionType.LOCATION, 0.0, Map.of("flight_number", "123"), now));

        var item = itemService.getItemById("item-mapped");

        assertNotNull(item);
        assertEquals(List.of("mapped-exit-a"), item.getDestinations());
        assertEquals("mapped-exit-a", item.getSelectedExitId());
        assertEquals(RoutingStatus.ASSIGNED, item.getRoutingStatus());
        assertEquals(List.of("mapped-start", "mapped-exit-a"), item.getPath());
    }

    @Test
    void itemCreatedWithoutResolvedDestinationDoesNotPersistFallbackPath() {
        Instant now = Instant.now();
        createLocation("destinationless-start", "Start");
        createLocation("destinationless-loop", "Loop");
        conveyorService.createConveyor("destinationless-main", "destinationless-start", "destinationless-loop",
                "Main Conveyor", 10.0, 1.0, 0.0, true, true);

        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent("item-destinationless",
                "Destinationless Item", 1.0, true, "destinationless-start", PositionType.LOCATION, 0.0,
                Map.of("flight_number", "999"), now));

        var item = itemService.getItemById("item-destinationless");

        assertNotNull(item);
        assertEquals(List.of(), item.getDestinations());
        assertNull(item.getSelectedExitId());
        assertEquals(RoutingStatus.UNROUTED, item.getRoutingStatus());
        assertNull(item.getPath());
    }

    @Test
    void unreachableExplicitDestinationFailsRouting() {
        Instant now = Instant.now();
        createLocation("failed-start", "Start");
        createLocation("failed-chute", "Chute", LocationType.CHUTE, 10);

        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent("item-failed",
                "Failed Item", 1.0, true, "failed-start", PositionType.LOCATION, 0.0,
                List.of("failed-chute"), Map.of(), now));

        var item = itemService.getItemById("item-failed");

        assertNotNull(item);
        assertNull(item.getSelectedExitId());
        assertEquals(RoutingStatus.FAILED, item.getRoutingStatus());
        assertEquals(now.toEpochMilli(), item.getRoutingStatusUpdatedAt().toEpochMilli());
        assertNull(item.getPath());
    }

    @Test
    void explicitItemDestinationBypassesDestinationMappingFallback() {
        Instant now = Instant.now();
        createLocation("explicit-start", "Start");
        createLocation("explicit-exit-a", "Exit A");
        createLocation("explicit-exit-b", "Exit B");
        conveyorService.createConveyor("explicit-conv-exit-a", "explicit-start", "explicit-exit-a", "Exit A Conveyor", 10.0, 1.0, 0.0,
                true, true);
        conveyorService.createConveyor("explicit-conv-exit-b", "explicit-start", "explicit-exit-b", "Exit B Conveyor", 10.0, 1.0, 0.0,
                false, true);

        eventProcessor.processEventWithoutBroadcast(new MapDestinationsEvent("flight_number", List.of(
                new DestinationMappingRecord("123", List.of("explicit-exit-a"), now.minusSeconds(60),
                        now.plusSeconds(3600)))));
        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent("item-explicit", "Explicit Item", 1.0, true,
                "explicit-start", PositionType.LOCATION, 0.0, List.of("explicit-exit-b"),
                Map.of("flight_number", "123"), now));

        var item = itemService.getItemById("item-explicit");

        assertNotNull(item);
        assertEquals(List.of("explicit-exit-b"), item.getDestinations());
        assertEquals("explicit-exit-b", item.getSelectedExitId());
        assertEquals(List.of("explicit-start", "explicit-exit-b"), item.getPath());
    }

    @Test
    void liveMappedItemCreationPublishesDestinationCommand() {
        Instant now = Instant.now();
        createMappedCommandTopology("command-start", "command-exit");

        eventProcessor.processEventWithoutBroadcast(new MapDestinationsEvent("flight_number", List.of(
                new DestinationMappingRecord("123", List.of("command-exit"), now.minusSeconds(60),
                        now.plusSeconds(3600)))));
        eventProcessor.processEvent(new ItemCreatedEvent("item-command", "Command Item", 1.0, true,
                "command-start", PositionType.LOCATION, 0.0, Map.of("flight_number", "123"), now), true);

        var item = itemService.getItemById("item-command");
        assertNotNull(item);
        assertEquals("command-exit", item.getSelectedExitId());

        ItemDestinationEvent destinationCommand = assertInstanceOf(ItemDestinationEvent.class,
                amqpTemplate.receiveAndConvert("commands", 2_000));
        assertEquals("ITEM_DESTINATION", destinationCommand.getEventType());
        assertEquals("item-command", destinationCommand.getEntityId());
        assertEquals("command-exit", destinationCommand.getLocationId());
        assertEquals(now, destinationCommand.getTimestamp());

        ItemPathAssignmentMessage pathAssignment = assertInstanceOf(ItemPathAssignmentMessage.class,
                amqpTemplate.receiveAndConvert("path-assignments", 2_000));
        assertEquals(ItemPathAssignmentMessage.MESSAGE_TYPE, pathAssignment.getMessageType());
        assertEquals("item-command", pathAssignment.getItemId());
        assertEquals("command-exit", pathAssignment.getFinalDestinationId());
        assertEquals(List.of("command-start", "command-exit"), pathAssignment.getPath());
        assertEquals(now, pathAssignment.getTimestamp());
    }

    @Test
    void itemCreationDoesNotPublishDestinationCommandWhenNoMappingMatches() {
        Instant now = Instant.now();
        createMappedCommandTopology("no-match-start", "no-match-exit");

        eventProcessor.processEventWithoutBroadcast(new MapDestinationsEvent("flight_number", List.of(
                new DestinationMappingRecord("123", List.of("no-match-exit"), now.minusSeconds(60),
                        now.plusSeconds(3600)))));
        eventProcessor.processEvent(new ItemCreatedEvent("item-no-match", "No Match Item", 1.0, true,
                "no-match-start", PositionType.LOCATION, 0.0, Map.of("flight_number", "999"), now), true);

        assertNull(amqpTemplate.receiveAndConvert("commands", 300));
        assertNull(amqpTemplate.receiveAndConvert("path-assignments", 300));
    }

    @Test
    void explicitItemDestinationDoesNotPublishDestinationCommand() {
        Instant now = Instant.now();
        createMappedCommandTopology("explicit-command-start", "explicit-command-exit");
        createLocation("explicit-command-other-exit", "Other Exit");
        conveyorService.createConveyor("explicit-command-conv-other", "explicit-command-start",
                "explicit-command-other-exit", "Other Exit Conveyor", 10.0, 1.0, 0.0, false, true);

        eventProcessor.processEventWithoutBroadcast(new MapDestinationsEvent("flight_number", List.of(
                new DestinationMappingRecord("123", List.of("explicit-command-exit"), now.minusSeconds(60),
                        now.plusSeconds(3600)))));
        eventProcessor.processEvent(new ItemCreatedEvent("item-explicit-command", "Explicit Command Item", 1.0, true,
                "explicit-command-start", PositionType.LOCATION, 0.0, List.of("explicit-command-other-exit"),
                Map.of("flight_number", "123"), now), true);

        ItemDestinationEvent command = assertInstanceOf(ItemDestinationEvent.class,
                amqpTemplate.receiveAndConvert("commands", 2_000));
        assertEquals("explicit-command-other-exit", command.getLocationId());
    }

    @Test
    void processEventWithoutBroadcastDoesNotPublishDestinationCommand() {
        Instant now = Instant.now();
        createMappedCommandTopology("without-broadcast-start", "without-broadcast-exit");

        eventProcessor.processEventWithoutBroadcast(new MapDestinationsEvent("flight_number", List.of(
                new DestinationMappingRecord("123", List.of("without-broadcast-exit"), now.minusSeconds(60),
                        now.plusSeconds(3600)))));
        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent("item-without-broadcast",
                "Without Broadcast Item", 1.0, true, "without-broadcast-start", PositionType.LOCATION, 0.0,
                Map.of("flight_number", "123"), now));

        assertNull(amqpTemplate.receiveAndConvert("commands", 300));
        assertNull(amqpTemplate.receiveAndConvert("path-assignments", 300));
    }

    @Test
    void simulationItemCreationDoesNotPublishDestinationCommand() {
        Instant now = Instant.now();

        orientDBService.createInMemoryDatabase(SIMULATION_ID);
        try (var ignored = DatabaseContextHolder.enterSimulationContext(SIMULATION_ID)) {
            createMappedCommandTopology("sim-command-start", "sim-command-exit");
            eventProcessor.processEventWithoutBroadcast(new MapDestinationsEvent("flight_number", List.of(
                    new DestinationMappingRecord("123", List.of("sim-command-exit"), now.minusSeconds(60),
                            now.plusSeconds(3600)))));
            eventProcessor.processEvent(new ItemCreatedEvent("item-sim-command", "Simulation Command Item", 1.0, true,
                    "sim-command-start", PositionType.LOCATION, 0.0, Map.of("flight_number", "123"), now), true);
        }

        assertNull(amqpTemplate.receiveAndConvert("commands", 300));
        assertNull(amqpTemplate.receiveAndConvert("path-assignments", 300));
    }

    @Test
    void destinationMappingSaveReplacesOldRowsAndEmptySaveClearsMappings() {
        Instant now = Instant.now();
        createLocation("replace-destination-a", "Destination A");
        createLocation("replace-destination-b", "Destination B");

        destinationMappingService.saveMapDestinations(new MapDestinationsEvent(null, List.of(
                mapping("flight", DataType.STRING, OperatorType.EQUAL, "KL123", "replace-destination-a", now))));
        destinationMappingService.saveMapDestinations(new MapDestinationsEvent(null, List.of(
                mapping("flight", DataType.STRING, OperatorType.EQUAL, "LH456", "replace-destination-b", now))));

        assertTrue(destinationMappingService.resolveDestinations(Map.of("flight", "KL123"), now).isEmpty());
        assertEquals(List.of("replace-destination-b"),
                destinationMappingService.resolveDestinations(Map.of("flight", "LH456"), now));

        destinationMappingService.saveMapDestinations(new MapDestinationsEvent(null, List.of()));

        assertTrue(destinationMappingService.resolveDestinations(Map.of("flight", "LH456"), now).isEmpty());
        assertTrue(destinationMappingService.getDestinationMappings().isEmpty());
    }

    @Test
    void mixedFieldRowsWorkInOneEventAndOldTopLevelFieldNameStillApplies() {
        Instant now = Instant.now();
        createLocation("mixed-destination-a", "Destination A");
        createLocation("mixed-destination-b", "Destination B");
        createLocation("legacy-destination", "Legacy Destination");

        destinationMappingService.saveMapDestinations(new MapDestinationsEvent(null, List.of(
                mapping("flight", DataType.STRING, OperatorType.EQUAL, "KL123", "mixed-destination-a", now),
                mapping("priority", DataType.NUMBER, OperatorType.GREATER, "10", "mixed-destination-b", now))));

        assertEquals(List.of("mixed-destination-a"),
                destinationMappingService.resolveDestinations(Map.of("flight", "KL123"), now));
        assertEquals(List.of("mixed-destination-b"),
                destinationMappingService.resolveDestinations(
                        Map.of("priority", 11), Map.of("priority", 0), now));

        destinationMappingService.saveMapDestinations(new MapDestinationsEvent("legacyField", List.of(
                new DestinationMappingRecord("legacy-value", List.of("legacy-destination"), now.minusSeconds(60),
                        now.plusSeconds(3600)))));

        assertEquals(List.of("legacy-destination"),
                destinationMappingService.resolveDestinations(Map.of("legacyField", "legacy-value"), now));
    }

    @Test
    void invalidDestinationMappingsAreRejected() {
        Instant now = Instant.now();
        createLocation("valid-destination", "Valid Destination");

        assertThrows(IllegalArgumentException.class, () -> destinationMappingService.saveMapDestinations(
                new MapDestinationsEvent(null, List.of(
                        mapping("flight", DataType.STRING, OperatorType.EQUAL, "KL123", "valid-destination", now),
                        mapping("flight", DataType.STRING, OperatorType.EQUAL, "KL123", "valid-destination", now)))));

        assertThrows(IllegalArgumentException.class, () -> destinationMappingService.saveMapDestinations(
                new MapDestinationsEvent(null, List.of(
                        new DestinationMappingRecord("", DataType.STRING, OperatorType.EQUAL, "KL123",
                                List.of("valid-destination"), now.minusSeconds(60), now.plusSeconds(3600))))));

        assertThrows(IllegalArgumentException.class, () -> destinationMappingService.saveMapDestinations(
                new MapDestinationsEvent(null, List.of(
                        new DestinationMappingRecord("flight", DataType.STRING, OperatorType.EQUAL, "",
                                List.of("valid-destination"), now.minusSeconds(60), now.plusSeconds(3600))))));

        assertThrows(IllegalArgumentException.class, () -> destinationMappingService.saveMapDestinations(
                new MapDestinationsEvent(null, List.of(
                        new DestinationMappingRecord("flight", DataType.STRING, OperatorType.EQUAL, "KL123",
                                List.of("valid-destination"), now.plusSeconds(3600), now.minusSeconds(60))))));
    }

    @Test
    void destinationMappingRushWindowValidationAcceptsBoundariesAndRejectsInvalidTimes() {
        Instant validFrom = Instant.now().minusSeconds(60);
        Instant validTo = validFrom.plusSeconds(3600);

        destinationMappingService.saveMapDestinations(new MapDestinationsEvent(null, List.of(
                rushMapping("flight", "KL123", "rush-destination", validFrom, validFrom, validTo))));
        assertEquals(validFrom, destinationMappingService.getDestinationMappings().getFirst().getRushAt());

        assertThrows(IllegalArgumentException.class, () -> destinationMappingService.saveMapDestinations(
                new MapDestinationsEvent(null, List.of(
                        rushMapping("flight", "KL123", "rush-destination", validFrom,
                                validFrom.minusMillis(1), validTo)))));
        assertThrows(IllegalArgumentException.class, () -> destinationMappingService.saveMapDestinations(
                new MapDestinationsEvent(null, List.of(
                        rushMapping("flight", "KL123", "rush-destination", validFrom, validTo, validTo)))));
    }

    @Test
    void rushPriorityRequiresRuleAndAssignedDestinationOverlapAndKeepsBasePriority() {
        Instant validFrom = Instant.now().minusSeconds(120);
        Instant rushAt = validFrom.plusSeconds(60);
        Instant validTo = rushAt.plusSeconds(60);
        destinationMappingService.saveMapDestinations(new MapDestinationsEvent(null, List.of(
                rushMapping("flight", "KL123", "rush-destination", validFrom, rushAt, validTo))));

        var item = new com.flunav.backend.domain.Item("rush-item", "Rush Item", true, 0.25,
                new java.util.HashMap<>(Map.of("flight", "KL123")));
        item.setDestinations(List.of("rush-destination"));

        assertEquals(0.25, destinationMappingService.evaluateRush(item, rushAt.minusMillis(1)).effectivePriority());
        assertFalse(destinationMappingService.evaluateRush(item, rushAt.minusMillis(1)).rushActive());
        assertEquals(1.0, destinationMappingService.evaluateRush(item, rushAt).effectivePriority());
        assertTrue(destinationMappingService.evaluateRush(item, validTo).rushActive());
        assertEquals(0.25, destinationMappingService.evaluateRush(item, validTo.plusMillis(1)).effectivePriority());
        assertEquals(0.25, item.getPriority());

        item.setDestinations(List.of("other-destination"));
        assertFalse(destinationMappingService.evaluateRush(item, rushAt).rushActive());
        item.setDestinations(List.of("rush-destination"));
        item.getProperties().put("flight", "LH456");
        assertFalse(destinationMappingService.evaluateRush(item, rushAt).rushActive());
    }

    @Test
    void rushEvaluationUsesTheActiveSimulationNamespace() {
        Instant validFrom = Instant.now().minusSeconds(60);
        Instant rushAt = validFrom.plusSeconds(10);
        Instant validTo = validFrom.plusSeconds(120);
        destinationMappingService.saveMapDestinations(new MapDestinationsEvent(null, List.of(
                rushMapping("flight", "LIVE", "live-destination", validFrom, rushAt, validTo))));

        var liveItem = new com.flunav.backend.domain.Item("live-rush-item", "Live", true, 0.2,
                Map.of("flight", "LIVE"));
        liveItem.setDestinations(List.of("live-destination"));
        var simulationItem = new com.flunav.backend.domain.Item("simulation-rush-item", "Simulation", true, 0.2,
                Map.of("flight", "SIM"));
        simulationItem.setDestinations(List.of("simulation-destination"));

        assertTrue(destinationMappingService.evaluateRush(liveItem, rushAt).rushActive());
        try (var ignored = DatabaseContextHolder.enterSimulationContext(SIMULATION_ID)) {
            assertFalse(destinationMappingService.evaluateRush(liveItem, rushAt).rushActive());
            destinationMappingService.saveMapDestinations(new MapDestinationsEvent(null, List.of(
                    rushMapping("flight", "SIM", "simulation-destination", validFrom, rushAt, validTo))));
            assertTrue(destinationMappingService.evaluateRush(simulationItem, rushAt).rushActive());
            assertFalse(destinationMappingService.evaluateRush(liveItem, rushAt).rushActive());
        }
        assertTrue(destinationMappingService.evaluateRush(liveItem, rushAt).rushActive());
        assertFalse(destinationMappingService.evaluateRush(simulationItem, rushAt).rushActive());
    }

    @Test
    void routingUsesEffectiveRushPriorityOnlyInsideTheWindow() {
        Instant validFrom = Instant.now().minusSeconds(60);
        Instant rushAt = validFrom.plusSeconds(20);
        Instant validTo = validFrom.plusSeconds(40);
        createLocation("rush-route-start", "Start", LocationType.DECISION_POINT, 0);
        createLocation("rush-route-short", "Short", LocationType.CHUTE, 10);
        createLocation("rush-route-open", "Open", LocationType.CHUTE, 10);
        conveyorService.createConveyor("rush-route-short-edge", "rush-route-start", "rush-route-short",
                "Short", 2.0, 1.0, 0.0, false, true);
        conveyorService.createConveyor("rush-route-open-edge", "rush-route-start", "rush-route-open",
                "Open", 20.0, 1.0, 0.0, false, true);
        for (int index = 0; index < 9; index++) {
            liveLocationRepository.addItemToLocation("rush-route-short", "rush-route-occupant-" + index);
        }
        destinationExitMappingService.saveMappings(new MapDestinationExitsEvent(List.of(
                new DestinationExitMappingRecord("rush-logical", List.of("rush-route-short", "rush-route-open")))));
        destinationMappingService.saveMapDestinations(new MapDestinationsEvent(null, List.of(
                rushMapping("flight", "KL123", "rush-logical", validFrom, rushAt, validTo))));

        var item = new com.flunav.backend.domain.Item("rush-route-item", "Rush Route", true, 0.0,
                Map.of("flight", "KL123"));
        item.setDestinations(List.of("rush-logical"));

        assertEquals("rush-route-open", routingDecisionService.selectRoute(
                item, "rush-route-start", PositionType.LOCATION, rushAt.minusMillis(1)).selectedExitId());
        assertEquals("rush-route-short", routingDecisionService.selectRoute(
                item, "rush-route-start", PositionType.LOCATION, rushAt).selectedExitId());
        assertEquals("rush-route-open", routingDecisionService.selectRoute(
                item, "rush-route-start", PositionType.LOCATION, validTo.plusMillis(1)).selectedExitId());
        for (int index = 0; index < 10; index++) {
            liveLocationRepository.addItemToLocation("rush-route-open", "rush-route-open-occupant-" + index);
        }
        liveLocationRepository.addItemToLocation("rush-route-short", "rush-route-occupant-9");
        assertEquals(RoutingStatus.UNROUTED, routingDecisionService.selectRoute(
                item, "rush-route-start", PositionType.LOCATION, rushAt.minusMillis(1)).routingStatus());
        assertEquals(RoutingStatus.WAITING_FOR_CAPACITY, routingDecisionService.selectRoute(
                item, "rush-route-start", PositionType.LOCATION, rushAt).routingStatus());
        assertEquals(0.0, item.getPriority());
    }

    @Test
    void graphSnapshotDerivesRushFieldsAtTheRequestedTimestamp() {
        Instant validFrom = Instant.now().minusSeconds(60);
        Instant rushAt = validFrom.plusSeconds(20);
        Instant validTo = validFrom.plusSeconds(40);
        createLocation("rush-graph-start", "Start");
        createItem("rush-graph-item", "Rush Graph", "rush-graph-start", validFrom, 0.3,
                Map.of("flight", "KL123"));
        liveItemRepository.updateRouting("rush-graph-item", List.of("rush-graph-destination"), null, null);
        destinationMappingService.saveMapDestinations(new MapDestinationsEvent(null, List.of(
                rushMapping("flight", "KL123", "rush-graph-destination", validFrom, rushAt, validTo))));

        ItemResponse before = graphService.getGraphData(rushAt.minusMillis(1), false).getItems().stream()
                .filter(item -> "rush-graph-item".equals(item.getId()))
                .findFirst()
                .orElseThrow();
        ItemResponse active = graphService.getGraphData(rushAt, false).getItems().stream()
                .filter(item -> "rush-graph-item".equals(item.getId()))
                .findFirst()
                .orElseThrow();

        assertEquals(0.3, before.getPriority());
        assertEquals(0.3, before.getEffectivePriority());
        assertFalse(before.getRushActive());
        assertEquals(0.3, active.getPriority());
        assertEquals(1.0, active.getEffectivePriority());
        assertTrue(active.getRushActive());
    }

    @Test
    void destinationMappingJsonRemainsBackwardCompatibleAndRoundTripsRushAt() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String legacyJson = "{\"fieldName\":\"flight\",\"dataType\":\"STRING\",\"operator\":\"EQUAL\","
                + "\"value\":\"KL123\",\"destinations\":[\"D1\"],\"validFrom\":\"2026-01-01T00:00:00Z\","
                + "\"validTo\":\"2026-01-02T00:00:00Z\"}";
        DestinationMappingRecord legacy = mapper.readValue(legacyJson, DestinationMappingRecord.class);
        assertNull(legacy.getRushAt());

        DestinationMappingRecord rush = rushMapping("flight", "KL123", "D1",
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T12:00:00Z"),
                Instant.parse("2026-01-02T00:00:00Z"));
        DestinationMappingRecord roundTrip = mapper.readValue(mapper.writeValueAsString(rush),
                DestinationMappingRecord.class);
        assertEquals(rush.getRushAt(), roundTrip.getRushAt());
    }

    @Test
    void destinationMappingsCanReferenceDestinationsThatDoNotExistYet() {
        Instant now = Instant.now();

        destinationMappingService.saveMapDestinations(
                new MapDestinationsEvent(null, List.of(
                        mapping("flight", DataType.STRING, OperatorType.EQUAL, "KL123", "missing-location", now))));

        assertEquals(List.of("missing-location"),
                destinationMappingService.resolveDestinations(Map.of("flight", "KL123"), now));
    }

    @Test
    void destinationMappingOperatorsAndDataTypesAffectMatching() {
        Instant now = Instant.now();
        createLocation("operator-destination-a", "Destination A");
        createLocation("operator-destination-b", "Destination B");
        createLocation("operator-destination-c", "Destination C");

        destinationMappingService.saveMapDestinations(new MapDestinationsEvent(null, List.of(
                mapping("weight", DataType.NUMBER, OperatorType.GREATER, "10", "operator-destination-a", now),
                mapping("cutoff", DataType.DATETIME, OperatorType.LESSER, "2026-01-01T00:00:00Z",
                        "operator-destination-b", now),
                mapping("fragile", DataType.BOOLEAN, OperatorType.EQUAL, "true", "operator-destination-c", now))));

        assertEquals(List.of("operator-destination-a"),
                destinationMappingService.resolveDestinations(Map.of("weight", "10.5"), now));
        assertEquals(List.of("operator-destination-b"),
                destinationMappingService.resolveDestinations(Map.of("cutoff", "2025-12-31T23:59:59Z"), now));
        assertEquals(List.of("operator-destination-c"),
                destinationMappingService.resolveDestinations(Map.of("fragile", "true"), now));
        assertTrue(destinationMappingService.resolveDestinations(Map.of("weight", "9"), now).isEmpty());
    }

    @Test
    void destinationMappingRangeUsesBothInclusiveAndExclusiveConditions() {
        Instant now = Instant.now();
        DestinationMappingRecord range = new DestinationMappingRecord(
                "priority", DataType.NUMBER, OperatorType.GREATER_OR_EQUAL, "0.4",
                OperatorType.LESSER, "0.8", List.of("range-destination"),
                now.minusSeconds(60), now.plusSeconds(3600));
        destinationMappingService.saveMapDestinations(new MapDestinationsEvent(null, List.of(range)));

        assertEquals(List.of("range-destination"),
                destinationMappingService.resolveDestinations(Map.of("priority", 0.4), Map.of(), now));
        assertTrue(destinationMappingService
                .resolveDestinations(Map.of("priority", 0.8), Map.of(), now).isEmpty());
    }

    @Test
    void destinationMappingOperatorAliasesMatchCanonicalOperators() {
        assertEquals(OperatorType.EQUAL, OperatorType.fromString("EQUALS"));
        assertEquals(OperatorType.GREATER, OperatorType.fromString("GREATER_THAN"));
        assertEquals(OperatorType.GREATER_OR_EQUAL, OperatorType.fromString("GTE"));
        assertEquals(OperatorType.LESSER, OperatorType.fromString("LESS_THAN"));
        assertEquals(OperatorType.LESSER_OR_EQUAL, OperatorType.fromString("LTE"));
    }

    @Test
    void simulationDestinationMappingsStayIsolatedFromLiveMappings() {
        Instant now = Instant.now();
        createLocation("live-destination", "Live Destination");

        destinationMappingService.saveMapDestinations(new MapDestinationsEvent(null, List.of(
                mapping("flight", DataType.STRING, OperatorType.EQUAL, "KL123", "live-destination", now))));

        orientDBService.createInMemoryDatabase(SIMULATION_ID);
        try (var ignored = DatabaseContextHolder.enterSimulationContext(SIMULATION_ID)) {
            createLocation("sim-destination", "Simulation Destination");
            destinationMappingService.saveMapDestinations(new MapDestinationsEvent(null, List.of(
                    mapping("flight", DataType.STRING, OperatorType.EQUAL, "KL123", "sim-destination", now))));

            assertEquals(List.of("sim-destination"),
                    destinationMappingService.resolveDestinations(Map.of("flight", "KL123"), now));
        }

        assertEquals(List.of("live-destination"),
                destinationMappingService.resolveDestinations(Map.of("flight", "KL123"), now));
    }

    @Test
    void selectsFirstReachableExitAcrossOrderedDestinationsAndExits() {
        Instant now = Instant.now();
        createLocation("routing-start", "Start");
        createLocation("routing-unreachable", "Unreachable");
        createLocation("routing-reachable", "Reachable");
        conveyorService.createConveyor("routing-conveyor", "routing-start", "routing-reachable",
                "Reachable Conveyor", 10.0, 1.0, 0.0, true, true);

        destinationExitMappingService.saveMappings(new MapDestinationExitsEvent(List.of(
                new DestinationExitMappingRecord("primary", List.of("routing-unreachable")),
                new DestinationExitMappingRecord("secondary", List.of("routing-reachable")))));

        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                "routing-item", "Routing Item", 1.0, true, "routing-start", PositionType.LOCATION, 0.0,
                List.of("primary", "secondary"), Map.of(), now));

        var item = itemService.getItemById("routing-item");
        assertEquals(List.of("primary", "secondary"), item.getDestinations());
        assertEquals("routing-reachable", item.getSelectedExitId());
        assertEquals(List.of("routing-start", "routing-reachable"), item.getPath());
    }

    @Test
    void normalPriorityItemSelectsAnotherChuteWhenTheShortestChuteIsAlmostFull() {
        Instant now = Instant.now();
        createLocation("capacity-start", "Start", LocationType.JUNCTION, 0);
        createLocation("capacity-stopped", "Stopped Chute", LocationType.CHUTE, 10);
        createLocation("capacity-near", "Near Chute", LocationType.CHUTE, 10);
        createLocation("capacity-far", "Far Chute", LocationType.CHUTE, 10);
        conveyorService.createConveyor("capacity-stopped-conveyor", "capacity-start", "capacity-stopped",
                "Stopped", 0.5, 0.0, 0.0, false, true);
        conveyorService.createConveyor("capacity-near-conveyor", "capacity-start", "capacity-near",
                "Near", 1.0, 1.0, 0.0, false, true);
        conveyorService.createConveyor("capacity-far-conveyor", "capacity-start", "capacity-far",
                "Far", 5.0, 1.0, 0.0, false, true);

        for (int index = 0; index < 9; index++) {
            liveLocationRepository.addItemToLocation("capacity-near", "occupant-" + index);
        }

        destinationExitMappingService.saveMappings(new MapDestinationExitsEvent(List.of(
                new DestinationExitMappingRecord("capacity-destination",
                        List.of("capacity-stopped", "capacity-near", "capacity-far")))));

        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                "capacity-normal-item", "Normal Item", 1.0, true, "capacity-start",
                PositionType.LOCATION, 0.0, List.of("capacity-destination"),
                Map.of(), now));

        var item = itemService.getItemById("capacity-normal-item");
        assertEquals("capacity-far", item.getSelectedExitId());
        assertEquals(List.of("capacity-start", "capacity-far"), item.getPath());
    }

    @Test
    void rapidNormalItemsReserveNoMoreThanProjectedChuteCapacity() {
        Instant now = Instant.now();
        createLocation("rapid-start", "Start", LocationType.JUNCTION, 0);
        createLocation("rapid-chute", "Chute", LocationType.CHUTE, 4);
        conveyorService.createConveyor("rapid-exit-conveyor", "rapid-start", "rapid-chute",
                "Exit", 1.0, 1.0, 0.0, false, true);

        for (int index = 0; index < 6; index++) {
            eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                    "rapid-item-" + index, "Rapid Item " + index, 1.0, true, "rapid-start",
                    PositionType.LOCATION, 0.0, List.of("rapid-chute"),
                    Map.of(), now.plusMillis(index)));
        }

        long selectedCount = itemService.getAllItems().stream()
                .filter(item -> "rapid-chute".equals(item.getSelectedExitId()))
                .count();

        assertEquals(4, selectedCount);
        assertNull(itemService.getItemById("rapid-item-4").getSelectedExitId());
        assertNull(itemService.getItemById("rapid-item-5").getSelectedExitId());
    }

    @Test
    void projectedOccupancyCountsItemsAssignedBeforeTheyPhysicallyArrive() {
        Instant now = Instant.now();
        createLocation("projected-start", "Start", LocationType.JUNCTION, 0);
        createLocation("projected-chute", "Chute", LocationType.CHUTE, 2);
        conveyorService.createConveyor("projected-exit-conveyor", "projected-start", "projected-chute",
                "Exit", 1.0, 1.0, 0.0, false, true);

        for (int index = 0; index < 2; index++) {
            eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                    "projected-item-" + index, "Projected Item " + index, 1.0, true, "projected-start",
                    PositionType.LOCATION, 0.0, List.of("projected-chute"),
                    Map.of(), now.plusMillis(index)));
        }
        assertEquals(0, liveLocationRepository.getItemCount("projected-chute"));

        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                "projected-item-2", "Projected Item 2", 1.0, true, "projected-start",
                PositionType.LOCATION, 0.0, List.of("projected-chute"),
                Map.of(), now.plusMillis(2)));

        assertEquals("projected-chute", itemService.getItemById("projected-item-0").getSelectedExitId());
        assertEquals("projected-chute", itemService.getItemById("projected-item-1").getSelectedExitId());
        assertNull(itemService.getItemById("projected-item-2").getSelectedExitId());
    }

    @Test
    void highPriorityCanUseReservedSpaceButCannotExceedProjectedHardCapacity() {
        Instant now = Instant.now();
        createLocation("priority-start", "Start", LocationType.JUNCTION, 0);
        createLocation("priority-chute", "Chute", LocationType.CHUTE, 10);
        conveyorService.createConveyor("priority-exit-conveyor", "priority-start", "priority-chute",
                "Exit", 1.0, 1.0, 0.0, false, true);

        for (int index = 0; index < 9; index++) {
            liveLocationRepository.addItemToLocation("priority-chute", "priority-occupant-" + index);
        }

        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                "priority-normal", "Normal", 1.0, 0.0, true, "priority-start",
                PositionType.LOCATION, 0.0, List.of("priority-chute"),
                Map.of(), now));
        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                "priority-high", "High", 1.0, 1.0, true, "priority-start",
                PositionType.LOCATION, 0.0, List.of("priority-chute"),
                Map.of(), now.plusMillis(1)));
        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                "priority-high-overflow", "High Overflow", 1.0, 1.0, true, "priority-start",
                PositionType.LOCATION, 0.0, List.of("priority-chute"),
                Map.of(), now.plusMillis(2)));

        assertNull(itemService.getItemById("priority-normal").getSelectedExitId());
        assertEquals("priority-chute", itemService.getItemById("priority-high").getSelectedExitId());
        assertNull(itemService.getItemById("priority-high-overflow").getSelectedExitId());
    }

    @Test
    void fractionalPriorityUsesProportionalReservedCapacity() {
        Instant now = Instant.now();
        createLocation("fractional-capacity-start", "Start", LocationType.JUNCTION, 0);
        createLocation("fractional-capacity-chute", "Chute", LocationType.CHUTE, 100);
        conveyorService.createConveyor("fractional-capacity-exit", "fractional-capacity-start",
                "fractional-capacity-chute", "Exit", 1.0, 1.0, 0.0, false, true);

        for (int index = 0; index < 94; index++) {
            liveLocationRepository.addItemToLocation("fractional-capacity-chute",
                    "fractional-capacity-occupant-" + index);
        }

        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                "fractional-capacity-item", "Fractional", 1.0, 0.45, true, "fractional-capacity-start",
                PositionType.LOCATION, 0.0, List.of("fractional-capacity-chute"),
                Map.of(), now));
        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                "fractional-capacity-overflow", "Fractional Overflow", 1.0, 0.45, true,
                "fractional-capacity-start", PositionType.LOCATION, 0.0,
                List.of("fractional-capacity-chute"), Map.of(), now.plusMillis(1)));

        assertEquals("fractional-capacity-chute",
                itemService.getItemById("fractional-capacity-item").getSelectedExitId());
        var overflow = itemService.getItemById("fractional-capacity-overflow");
        assertNull(overflow.getSelectedExitId());
        assertEquals(RoutingStatus.WAITING_FOR_CAPACITY, overflow.getRoutingStatus());
    }

    @Test
    void fractionalPriorityBlendsUtilizationAndTravelTime() {
        Instant now = Instant.now();
        createLocation("fractional-route-start", "Start", LocationType.JUNCTION, 0);
        createLocation("fractional-route-near", "Near Chute", LocationType.CHUTE, 10);
        createLocation("fractional-route-far", "Far Chute", LocationType.CHUTE, 10);
        conveyorService.createConveyor("fractional-route-near-conveyor", "fractional-route-start",
                "fractional-route-near", "Near", 1.0, 1.0, 0.0, false, true);
        conveyorService.createConveyor("fractional-route-far-conveyor", "fractional-route-start",
                "fractional-route-far", "Far", 5.0, 1.0, 0.0, false, true);

        for (int index = 0; index < 9; index++) {
            liveLocationRepository.addItemToLocation("fractional-route-near",
                    "fractional-route-near-occupant-" + index);
        }

        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                "fractional-route-balanced", "Balanced Priority", 1.0, 0.45, true, "fractional-route-start",
                PositionType.LOCATION, 0.0, List.of("fractional-route-near", "fractional-route-far"),
                Map.of(), now));
        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                "fractional-route-urgent", "Urgent Priority", 1.0, 0.80, true, "fractional-route-start",
                PositionType.LOCATION, 0.0, List.of("fractional-route-near", "fractional-route-far"),
                Map.of(), now.plusMillis(1)));

        assertEquals("fractional-route-far",
                itemService.getItemById("fractional-route-balanced").getSelectedExitId());
        assertEquals("fractional-route-near",
                itemService.getItemById("fractional-route-urgent").getSelectedExitId());
    }

    @Test
    void highPriorityWaitsForCapacityWhenAllCandidateChutesAreFull() {
        Instant now = Instant.now();
        createLocation("wait-start", "Start", LocationType.JUNCTION, 0);
        createLocation("wait-chute-a", "Chute A", LocationType.CHUTE, 1);
        createLocation("wait-chute-b", "Chute B", LocationType.CHUTE, 1);
        createLocation("wait-loop", "Loop", LocationType.JUNCTION, 0);
        conveyorService.createConveyor("wait-a", "wait-start", "wait-chute-a", "A", 10_000.0, 1.0, 0.0,
                false, true);
        conveyorService.createConveyor("wait-b", "wait-start", "wait-chute-b", "B", 10_000.0, 1.0, 0.0,
                false, true);
        conveyorService.createConveyor("wait-loop-conveyor", "wait-start", "wait-loop", "Loop", 10_000.0, 1.0,
                0.0, true, true);
        liveLocationRepository.addItemToLocation("wait-chute-a", "occupant-a");
        liveLocationRepository.addItemToLocation("wait-chute-b", "occupant-b");

        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                "wait-high", "High", 1.0, 1.0, true, "wait-start", PositionType.LOCATION, 0.0,
                List.of("wait-chute-a", "wait-chute-b"), Map.of(), now));

        var item = itemService.getItemById("wait-high");
        assertNull(item.getSelectedExitId());
        assertEquals(RoutingStatus.WAITING_FOR_CAPACITY, item.getRoutingStatus());
        assertEquals(List.of("wait-start", "wait-loop"), item.getPath());
    }

    @Test
    void chuteEmptyAssignsWaitingHighPriorityBeforeNewNormalItemConsumesFreedSlot() {
        Instant now = Instant.now();
        createLocation("retry-start", "Start", LocationType.JUNCTION, 0);
        createLocation("retry-chute", "Chute", LocationType.CHUTE, 1);
        createLocation("retry-loop", "Loop", LocationType.JUNCTION, 0);
        conveyorService.createConveyor("retry-exit", "retry-start", "retry-chute", "Exit", 10_000.0, 1.0, 0.0,
                false, true);
        conveyorService.createConveyor("retry-loop-conveyor", "retry-start", "retry-loop", "Loop", 10_000.0, 1.0,
                0.0, true, true);
        conveyorService.createConveyor("retry-loop-back", "retry-loop", "retry-start", "Loop Back", 10_000.0, 1.0,
                0.0, true, true);
        liveLocationRepository.addItemToLocation("retry-chute", "retry-occupant");

        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                "retry-high", "High", 1.0, 1.0, true, "retry-start", PositionType.LOCATION, 0.0,
                List.of("retry-chute"), Map.of(), now));
        assertEquals(RoutingStatus.WAITING_FOR_CAPACITY, itemService.getItemById("retry-high").getRoutingStatus());

        eventProcessor.processEventWithoutBroadcast(new ChuteEmptyEvent("retry-chute", now.plusSeconds(1)));
        var high = itemService.getItemById("retry-high");
        assertEquals("retry-chute", high.getSelectedExitId());
        assertEquals(RoutingStatus.ASSIGNED, high.getRoutingStatus());

        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                "retry-normal", "Normal", 1.0, true, "retry-start", PositionType.LOCATION, 0.0,
                List.of("retry-chute"), Map.of(), now.plusSeconds(2)));
        assertNull(itemService.getItemById("retry-normal").getSelectedExitId());
    }

    @Test
    void normalItemIsBlockedByOverlappingPendingHighPriorityReservation() {
        Instant now = Instant.now();
        createLocation("pending-start", "Start", LocationType.JUNCTION, 0);
        createLocation("pending-chute", "Chute", LocationType.CHUTE, 10);
        createLocation("pending-loop", "Loop", LocationType.JUNCTION, 0);
        conveyorService.createConveyor("pending-exit", "pending-start", "pending-chute", "Exit", 10_000.0, 1.0,
                0.0, false, true);
        conveyorService.createConveyor("pending-loop-conveyor", "pending-start", "pending-loop", "Loop", 10_000.0,
                1.0, 0.0, true, true);
        for (int index = 0; index < 10; index++) {
            liveLocationRepository.addItemToLocation("pending-chute", "pending-occupant-" + index);
        }

        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                "pending-high", "High", 1.0, 1.0, true, "pending-start", PositionType.LOCATION, 0.0,
                List.of("pending-chute"), Map.of(), now));
        for (int index = 0; index < 1; index++) {
            liveLocationRepository.removeItemFromLocation("pending-chute", "pending-occupant-" + index);
        }

        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                "pending-normal", "Normal", 1.0, true, "pending-start", PositionType.LOCATION, 0.0,
                List.of("pending-chute"), Map.of(), now.plusSeconds(1)));

        assertNull(itemService.getItemById("pending-normal").getSelectedExitId());
        assertEquals(RoutingStatus.UNROUTED, itemService.getItemById("pending-normal").getRoutingStatus());
    }

    @Test
    void pendingReservationsUseOneBestCandidateInsteadOfEveryCandidateExit() {
        Instant now = Instant.now();
        createLocation("split-start", "Start", LocationType.JUNCTION, 0);
        createLocation("split-chute-a", "Chute A", LocationType.CHUTE, 10);
        createLocation("split-chute-b", "Chute B", LocationType.CHUTE, 10);
        conveyorService.createConveyor("split-a", "split-start", "split-chute-a", "A", 10_000.0, 1.0, 0.0,
                false, true);
        conveyorService.createConveyor("split-b", "split-start", "split-chute-b", "B", 20_000.0, 1.0, 0.0,
                false, true);
        for (String chuteId : List.of("split-chute-a", "split-chute-b")) {
            for (int index = 0; index < 10; index++) {
                liveLocationRepository.addItemToLocation(chuteId, chuteId + "-occupant-" + index);
            }
        }

        for (int index = 0; index < 2; index++) {
            eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                    "split-high-" + index, "High " + index, 1.0, 1.0, true, "split-start", PositionType.LOCATION,
                    0.0, List.of("split-chute-a", "split-chute-b"), Map.of(),
                    now.plusMillis(index)));
        }
        for (String chuteId : List.of("split-chute-a", "split-chute-b")) {
            for (int index = 0; index < 3; index++) {
                liveLocationRepository.removeItemFromLocation(chuteId, chuteId + "-occupant-" + index);
            }
        }

        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                "split-normal", "Normal", 1.0, true, "split-start", PositionType.LOCATION, 0.0,
                List.of("split-chute-a", "split-chute-b"), Map.of(), now.plusSeconds(1)));

        assertNotNull(itemService.getItemById("split-normal").getSelectedExitId());
    }

    @Test
    void pendingReservationCapPreventsHighPriorityFloodFromBlockingAllNormalRouting() {
        Instant now = Instant.now();
        createLocation("cap-start", "Start", LocationType.JUNCTION, 0);
        createLocation("cap-chute", "Chute", LocationType.CHUTE, 10);
        conveyorService.createConveyor("cap-exit", "cap-start", "cap-chute", "Exit", 10_000.0, 1.0, 0.0,
                false, true);
        for (int index = 0; index < 10; index++) {
            liveLocationRepository.addItemToLocation("cap-chute", "cap-occupant-" + index);
        }
        for (int index = 0; index < 6; index++) {
            eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                    "cap-high-" + index, "High " + index, 1.0, 1.0, true, "cap-start", PositionType.LOCATION, 0.0,
                    List.of("cap-chute"), Map.of(), now.plusMillis(index)));
        }
        for (int index = 0; index < 5; index++) {
            liveLocationRepository.removeItemFromLocation("cap-chute", "cap-occupant-" + index);
        }

        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                "cap-normal", "Normal", 1.0, true, "cap-start", PositionType.LOCATION, 0.0,
                List.of("cap-chute"), Map.of(), now.plusSeconds(1)));

        assertEquals("cap-chute", itemService.getItemById("cap-normal").getSelectedExitId());
    }

    @Test
    void simulationPendingReservationsDoNotConsumeLiveCapacity() {
        Instant now = Instant.now();
        createLocation("sim-live-start", "Start", LocationType.JUNCTION, 0);
        createLocation("sim-live-chute", "Chute", LocationType.CHUTE, 10);
        conveyorService.createConveyor("sim-live-exit", "sim-live-start", "sim-live-chute", "Exit", 10_000.0, 1.0,
                0.0, false, true);
        for (int index = 0; index < 10; index++) {
            liveLocationRepository.addItemToLocation("sim-live-chute", "sim-live-occupant-" + index);
        }
        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                "sim-live-high", "High", 1.0, 1.0, true, "sim-live-start", PositionType.LOCATION, 0.0,
                List.of("sim-live-chute"), Map.of(), now));
        for (int index = 0; index < 1; index++) {
            liveLocationRepository.removeItemFromLocation("sim-live-chute", "sim-live-occupant-" + index);
        }
        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                "sim-live-normal", "Normal", 1.0, true, "sim-live-start", PositionType.LOCATION, 0.0,
                List.of("sim-live-chute"), Map.of(), now.plusSeconds(1)));
        assertNull(itemService.getItemById("sim-live-normal").getSelectedExitId());

        orientDBService.createInMemoryDatabase(SIMULATION_ID);
        try (var ignored = DatabaseContextHolder.enterSimulationContext(SIMULATION_ID)) {
            createLocation("sim-start", "Start", LocationType.JUNCTION, 0);
            createLocation("sim-chute", "Chute", LocationType.CHUTE, 10);
            conveyorService.createConveyor("sim-exit", "sim-start", "sim-chute", "Exit", 10_000.0, 1.0,
                    0.0, false, true);
            for (int index = 0; index < 8; index++) {
                liveLocationRepository.addItemToLocation("sim-chute", "sim-occupant-" + index);
            }
            eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                    "sim-normal", "Normal", 1.0, true, "sim-start", PositionType.LOCATION, 0.0,
                    List.of("sim-chute"), Map.of(), now.plusSeconds(2)));
            assertEquals("sim-chute", itemService.getItemById("sim-normal").getSelectedExitId());
        }
    }

    @Test
    void chuteEntryGuardBlocksStraightMultiHopArrivalWhenProjectedCapacityIsFull() {
        Instant future = Instant.now().plusSeconds(60);
        createLocation("guard-a", "A", LocationType.JUNCTION, 0);
        createLocation("guard-b", "B", LocationType.JUNCTION, 0);
        createLocation("guard-c", "C", LocationType.JUNCTION, 0);
        createLocation("guard-d", "D", LocationType.CHUTE, 1);
        conveyorService.createConveyor("guard-ab", "guard-a", "guard-b", "AB", 10.0, 1.0, 1.0, true, true);
        conveyorService.createConveyor("guard-bc", "guard-b", "guard-c", "BC", 10.0, 1.0, 1.0, true, true);
        conveyorService.createConveyor("guard-cd", "guard-c", "guard-d", "CD", 10.0, 1.0, 1.0, true, true);

        createItem("guard-reserved", "Reserved", "guard-b", future, Map.of());
        liveItemRepository.updatePosition("guard-reserved", "guard-bc", PositionType.CONVEYOR, future, 0.0, null);
        liveItemRepository.updateRouting("guard-reserved", List.of("guard-d"), "guard-d",
                List.of("guard-b", "guard-c", "guard-d"));

        createItem("guard-extra", "Extra", "guard-c", future, Map.of());
        liveItemRepository.updatePosition("guard-extra", "guard-cd", PositionType.CONVEYOR, future, 0.0, null);
        liveItemRepository.updateRouting("guard-extra", List.of("guard-d"), "guard-d",
                List.of("guard-c", "guard-d"));

        try {
            itemMovementProcessor.handleItemEntryToConveyor("guard-extra", "guard-cd", future, 0.0, null);

            ItemPositionChangedEvent scheduled = assertInstanceOf(ItemPositionChangedEvent.class,
                    itemMovementProcessor.getScheduledEvent("guard-extra"));
            assertEquals("guard-cd", scheduled.getLocationId());
            assertTrue(scheduled.getProgress() < 100.0);
        } finally {
            itemMovementProcessor.cancelScheduledEvent("guard-extra");
        }
    }

    @Test
    void logicalDestinationMappingKeepsLogicalDestinationAndStoresPhysicalRouteThroughRoutingBuffer() {
        Instant now = timeService.physicalNow();
        createLocation("logical-decision", "Decision", LocationType.DECISION_POINT, 0);
        createLocation("logical-routing-buffer", "Routing Buffer", LocationType.JUNCTION, 0);
        createLocation("logical-reserved-chute", "Reserved Chute", LocationType.CHUTE, 10);
        createLocation("logical-open-chute", "Open Chute", LocationType.CHUTE, 10);
        createLocation("logical-loop", "Loop", LocationType.JUNCTION, 0);
        conveyorService.createConveyor("logical-decision-buffer", "logical-decision", "logical-routing-buffer",
                "Decision Buffer", 2.0, 1.0, 0.0, false, true);
        conveyorService.createConveyor("logical-decision-loop", "logical-decision", "logical-loop",
                "Decision Loop", 2.0, 1.0, 0.0, true, true);
        conveyorService.createConveyor("logical-buffer-reserved", "logical-routing-buffer",
                "logical-reserved-chute", "Reserved", 2.0, 1.0, 0.0, false, true);
        conveyorService.createConveyor("logical-buffer-open", "logical-routing-buffer",
                "logical-open-chute", "Open", 2.0, 1.0, 0.0, false, true);

        for (int index = 0; index < 9; index++) {
            liveLocationRepository.addItemToLocation("logical-reserved-chute", "logical-occupant-" + index);
        }

        destinationExitMappingService.saveMappings(new MapDestinationExitsEvent(List.of(
                new DestinationExitMappingRecord("logical-destination",
                        List.of("logical-reserved-chute", "logical-open-chute")))));
        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                "logical-item", "Logical Item", 1.0, true, "logical-decision",
                PositionType.LOCATION, 0.0, List.of("logical-destination"),
                Map.of(), now));

        var item = itemService.getItemById("logical-item");
        assertNotNull(item);
        assertEquals(List.of("logical-destination"), item.getDestinations());
        assertEquals("logical-open-chute", item.getSelectedExitId());
        assertEquals(List.of("logical-decision", "logical-routing-buffer", "logical-open-chute"),
                item.getPath());
    }

    @Test
    void scheduledDecisionPointTransitionRecalculatesFallbackLogicalDestinationRoute() {
        Instant now = timeService.physicalNow();
        createLocation("scheduled-entry", "Entry", LocationType.JUNCTION, 0);
        createLocation("scheduled-decision", "Decision", LocationType.DECISION_POINT, 0);
        createLocation("scheduled-routing-buffer", "Routing Buffer", LocationType.JUNCTION, 0);
        createLocation("scheduled-chute", "Chute", LocationType.CHUTE, 10);
        createLocation("scheduled-loop", "Loop", LocationType.JUNCTION, 0);
        conveyorService.createConveyor("scheduled-entry-decision", "scheduled-entry", "scheduled-decision",
                "Entry Decision", 2.0, 1.0, 0.0, true, true);
        conveyorService.createConveyor("scheduled-decision-buffer", "scheduled-decision",
                "scheduled-routing-buffer", "Decision Buffer", 2.0, 1.0, 0.0, false, true);
        conveyorService.createConveyor("scheduled-decision-loop", "scheduled-decision", "scheduled-loop",
                "Decision Loop", 2.0, 1.0, 0.0, true, true);
        conveyorService.createConveyor("scheduled-buffer-chute", "scheduled-routing-buffer",
                "scheduled-chute", "Chute", 2.0, 1.0, 0.0, false, true);

        destinationExitMappingService.saveMappings(new MapDestinationExitsEvent(List.of(
                new DestinationExitMappingRecord("scheduled-destination", List.of("scheduled-chute")))));
        ItemInput item = new ItemInput();
        item.setId("scheduled-item");
        item.setName("Scheduled Item");
        item.setActive(true);
        item.setLocationId("scheduled-entry-decision");
        item.setPositionType(PositionType.CONVEYOR);
        item.setProgress(0.0);
        item.setTimestamp(now);
        item.setDestinations(List.of("scheduled-destination"));
        item.setSelectedExitId(null);
        item.setPath(List.of("scheduled-entry", "scheduled-decision"));
        item.setPriority(0.0);
        item.setProperties(Map.of());
        itemService.createItem(item);
        liveConveyorRepository.addItemToConveyor("scheduled-entry-decision", "scheduled-item", now);

        itemMovementProcessor.handleItemEntryToConveyor("scheduled-item", "scheduled-entry-decision", now, 0.0,
                null);

        var scheduled = assertInstanceOf(ItemPositionChangedEvent.class,
                itemMovementProcessor.getScheduledEvent("scheduled-item"));
        assertEquals("scheduled-decision-buffer", scheduled.getLocationId());

        var routedItem = itemService.getItemById("scheduled-item");
        assertNotNull(routedItem);
        assertEquals(List.of("scheduled-destination"), routedItem.getDestinations());
        assertEquals("scheduled-chute", routedItem.getSelectedExitId());
        assertEquals(List.of("scheduled-decision", "scheduled-routing-buffer", "scheduled-chute"),
                routedItem.getPath());
    }

    @Test
    void decisionPointPositionUpdateDoesNotRecalculateRoutingWhenManageLogicDisabled() {
        Instant now = timeService.physicalNow();
        createLocation("disabled-decision", "Decision", LocationType.DECISION_POINT, 0);
        createLocation("disabled-chute", "Chute", LocationType.CHUTE, 1);
        createLocation("disabled-loop", "Loop", LocationType.JUNCTION, 0);
        conveyorService.createConveyor("disabled-exit-conveyor", "disabled-decision", "disabled-chute",
                "Exit", 1.0, 1.0, 0.0, false, true);
        conveyorService.createConveyor("disabled-main-conveyor", "disabled-decision", "disabled-loop",
                "Main", 2.0, 1.0, 0.0, true, true);

        createItem("disabled-item", "Item", "disabled-decision", now, Map.of());
        List<String> existingPath = List.of("disabled-decision", "disabled-loop");
        liveItemRepository.updateRouting("disabled-item", List.of("disabled-chute"), null, existingPath);
        itemService.updateItemPosition("disabled-item", "disabled-decision", PositionType.LOCATION, now, 0.0, null);

        ItemMovementProcessor disabledProcessor = new ItemMovementProcessor(
                amqpTemplate,
                "1",
                liveConveyorRepository,
                liveItemRepository,
                liveLocationRepository,
                topologyProvider,
                itemService,
                routingDecisionService,
                new com.flunav.backend.services.RoutingCoordinator(),
                simulationService,
                liveSystemScheduler,
                timeService,
                pathAssignmentPublisher,
                webSocketService,
                operationalAnalyticsService,
                false);
        disabledProcessor.processLocationEntry("disabled-item", "disabled-decision", now);

        var item = itemService.getItemById("disabled-item");
        assertNotNull(item);
        assertNull(item.getSelectedExitId());
        assertEquals(existingPath, item.getPath());
        assertNull(disabledProcessor.getScheduledEvent("disabled-item"));
    }

    @Test
    void decisionPointUsesMainPathForNormalPriorityAndExitForHighPriority() {
        Instant now = Instant.now();
        createLocation("decision-point", "Decision", LocationType.DECISION_POINT, 0);
        createLocation("decision-chute", "Chute", LocationType.CHUTE, 10);
        createLocation("decision-loop", "Loop", LocationType.JUNCTION, 0);
        conveyorService.createConveyor("decision-exit-conveyor", "decision-point", "decision-chute",
                "Exit", 1.0, 1.0, 0.0, false, true);
        conveyorService.createConveyor("decision-main-conveyor", "decision-point", "decision-loop",
                "Main", 2.0, 1.0, 0.0, true, true);

        for (int index = 0; index < 9; index++) {
            liveLocationRepository.addItemToLocation("decision-chute", "decision-occupant-" + index);
        }

        createItem("decision-normal", "Normal", "decision-point", now, 0.0, Map.of());
        liveItemRepository.updateRouting(
                "decision-normal", List.of("decision-chute"), null, null);
        eventProcessor.process(
                new ItemPositionChangedEvent("decision-normal", "decision-point", 0.0, now), true).join();

        var normalItem = waitForPath("decision-normal", List.of("decision-point", "decision-loop"));
        assertNull(normalItem.getSelectedExitId());
        assertNull(amqpTemplate.receiveAndConvert("commands", 300));

        createItem("decision-high", "High", "decision-point", now, 1.0, Map.of());
        liveItemRepository.updateRouting(
                "decision-high", List.of("decision-chute"), null, null);
        eventProcessor.process(
                new ItemPositionChangedEvent("decision-high", "decision-point", 0.0, now), true).join();

        var highItem = waitForPath("decision-high", List.of("decision-point", "decision-chute"));
        assertEquals("decision-chute", highItem.getSelectedExitId());
        assertNull(amqpTemplate.receiveAndConvert("commands", 300));
    }

    @Test
    void priorityUpdateKeepsCurrentRouteUntilNextDecisionPoint() {
        Instant now = Instant.now();
        createLocation("priority-update-decision", "Decision", LocationType.DECISION_POINT, 0);
        createLocation("priority-update-chute", "Chute", LocationType.CHUTE, 10);
        createLocation("priority-update-loop", "Loop", LocationType.JUNCTION, 0);
        conveyorService.createConveyor("priority-update-exit", "priority-update-decision", "priority-update-chute",
                "Exit", 1.0, 1.0, 0.0, false, true);
        conveyorService.createConveyor("priority-update-main", "priority-update-decision", "priority-update-loop",
                "Main", 2.0, 1.0, 0.0, true, true);
        for (int index = 0; index < 9; index++) {
            liveLocationRepository.addItemToLocation("priority-update-chute", "priority-update-occupant-" + index);
        }

        createItem("priority-update-item", "Item", "priority-update-decision", now, 0.0, Map.of());
        liveItemRepository.updateRouting(
                "priority-update-item", List.of("priority-update-chute"), null, null);
        eventProcessor.processEventWithoutBroadcast(
                new ItemRoutingDecisionRequestedEvent("priority-update-item", "priority-update-decision", now));
        assertEquals(List.of("priority-update-decision", "priority-update-loop"),
                itemService.getItemById("priority-update-item").getPath());

        eventProcessor.processEventWithoutBroadcast(
                new ItemPriorityUpdatedEvent("priority-update-item", 1.0, now.plusMillis(1)));
        assertEquals(List.of("priority-update-decision", "priority-update-loop"),
                itemService.getItemById("priority-update-item").getPath());

        eventProcessor.processEventWithoutBroadcast(
                new ItemRoutingDecisionRequestedEvent(
                        "priority-update-item", "priority-update-decision", now.plusMillis(2)));
        assertEquals(List.of("priority-update-decision", "priority-update-chute"),
                itemService.getItemById("priority-update-item").getPath());
    }

    @Test
    void decisionPointSelectsFreeCompatibleChuteBeforeRecirculatingNormalPriorityItem() {
        Instant now = Instant.now();
        createLocation("free-decision", "Decision", LocationType.DECISION_POINT, 0);
        createLocation("free-reserved-chute", "Reserved Chute", LocationType.CHUTE, 10);
        createLocation("free-open-chute", "Open Chute", LocationType.CHUTE, 10);
        createLocation("free-loop", "Loop", LocationType.JUNCTION, 0);
        conveyorService.createConveyor("free-reserved-conveyor", "free-decision", "free-reserved-chute",
                "Reserved", 1.0, 1.0, 0.0, false, true);
        conveyorService.createConveyor("free-open-conveyor", "free-decision", "free-open-chute",
                "Open", 2.0, 1.0, 0.0, false, true);
        conveyorService.createConveyor("free-main-conveyor", "free-decision", "free-loop",
                "Main", 3.0, 1.0, 0.0, true, true);

        for (int index = 0; index < 9; index++) {
            liveLocationRepository.addItemToLocation("free-reserved-chute", "free-reserved-occupant-" + index);
        }

        destinationExitMappingService.saveMappings(new MapDestinationExitsEvent(List.of(
                new DestinationExitMappingRecord("free-destination",
                        List.of("free-reserved-chute", "free-open-chute")))));

        createItem("free-normal", "Normal", "free-decision", now, Map.of());
        liveItemRepository.updateRouting("free-normal", List.of("free-destination"), null, null);
        eventProcessor.process(
                new ItemPositionChangedEvent("free-normal", "free-decision", 0.0, now), true).join();

        var normalItem = waitForPath("free-normal", List.of("free-decision", "free-open-chute"));
        assertEquals("free-open-chute", normalItem.getSelectedExitId());
        assertNull(amqpTemplate.receiveAndConvert("commands", 300));
    }

    @Test
    void stoppedExitConveyorAtDecisionPointReroutesAssignedItemToAvailableExit() {
        Instant now = Instant.now();
        createLocation("stop-route-decision", "Decision", LocationType.DECISION_POINT, 0);
        createLocation("stop-route-preferred", "Preferred Chute", LocationType.CHUTE, 10);
        createLocation("stop-route-alternate", "Alternate Chute", LocationType.CHUTE, 10);
        conveyorService.createConveyor("stop-route-preferred-conveyor", "stop-route-decision",
                "stop-route-preferred", "Preferred", 10_000.0, 1.0, 0.0, false, true);
        conveyorService.createConveyor("stop-route-alternate-conveyor", "stop-route-decision",
                "stop-route-alternate", "Alternate", 20_000.0, 1.0, 0.0, false, true);

        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                "stop-route-item", "Stop Route Item", 1.0, true, "stop-route-decision",
                PositionType.LOCATION, 0.0, List.of("stop-route-preferred", "stop-route-alternate"),
                Map.of(), now));

        var initiallyRouted = itemService.getItemById("stop-route-item");
        assertEquals("stop-route-preferred", initiallyRouted.getSelectedExitId());
        assertEquals(RoutingStatus.ASSIGNED, initiallyRouted.getRoutingStatus());

        eventProcessor.processEventWithoutBroadcast(new ConnectionDeactivatedEvent("stop-route-preferred-conveyor"));
        eventProcessor.processEventWithoutBroadcast(
                new ItemRoutingDecisionRequestedEvent("stop-route-item", "stop-route-decision"));

        var rerouted = itemService.getItemById("stop-route-item");
        assertEquals("stop-route-alternate", rerouted.getSelectedExitId());
        assertEquals(List.of("stop-route-decision", "stop-route-alternate"), rerouted.getPath());
        assertEquals(RoutingStatus.ASSIGNED, rerouted.getRoutingStatus());
        assertNotNull(rerouted.getRoutingStatusUpdatedAt());
        assertNull(amqpTemplate.receiveAndConvert("path-assignments", 300));
    }

    @Test
    void deactivatedConveyorFreezesScheduledItemUntilReactivated() {
        Instant now = Instant.now();
        createLocation("stop-motion-start", "Start", LocationType.JUNCTION, 0);
        createLocation("stop-motion-exit", "Exit", LocationType.CHUTE, 10);
        conveyorService.createConveyor("stop-motion-conveyor", "stop-motion-start", "stop-motion-exit",
                "Stop Motion", 10_000.0, 1.0, 0.0, false, true);

        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                "stop-motion-item", "Stop Motion Item", 1.0, true, "stop-motion-start",
                PositionType.LOCATION, 0.0, List.of("stop-motion-exit"), Map.of(), now));

        assertNotNull(itemMovementProcessor.getScheduledEvent("stop-motion-item"));

        eventProcessor.processEventWithoutBroadcast(new ConnectionDeactivatedEvent("stop-motion-conveyor"));

        assertNull(itemMovementProcessor.getScheduledEvent("stop-motion-item"));
        assertEquals("stop-motion-conveyor",
                liveItemRepository.getItemState("stop-motion-item").getPositionId());

        eventProcessor.processEventWithoutBroadcast(new ConnectionActivatedEvent("stop-motion-conveyor"));

        assertNotNull(itemMovementProcessor.getScheduledEvent("stop-motion-item"));
    }

    @Test
    void itemEnteringChuteBecomesCompletedUntilChuteIsEmptied() {
        Instant now = Instant.now();
        Instant arrivedAt = now.plusSeconds(10);
        createLocation("completed-start", "Start", LocationType.JUNCTION, 0);
        createLocation("completed-chute", "Chute", LocationType.CHUTE, 10);
        conveyorService.createConveyor("completed-conveyor", "completed-start", "completed-chute",
                "Exit", 10.0, 1.0, 0.0, false, true);

        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                "completed-item", "Completed Item", 1.0, true, "completed-start",
                PositionType.LOCATION, 0.0, List.of("completed-chute"),
                Map.of(), now));

        var assigned = itemService.getItemById("completed-item");
        assertEquals(RoutingStatus.ASSIGNED, assigned.getRoutingStatus());
        assertEquals("completed-chute", assigned.getSelectedExitId());

        eventProcessor.processEventWithoutBroadcast(new ItemPositionChangedEvent(
                "completed-item", "completed-chute", 100.0, arrivedAt));

        var completed = itemService.getItemById("completed-item");
        assertNotNull(completed);
        assertEquals("completed-chute", completed.getPositionId());
        assertEquals(RoutingStatus.COMPLETED, completed.getRoutingStatus());
        assertEquals(arrivedAt.toEpochMilli(), completed.getRoutingStatusUpdatedAt().toEpochMilli());
        assertEquals(1L, liveLocationRepository.getItemCount("completed-chute"));

        List<ItemResponse> graphItems = graphService.getGraphData(arrivedAt, false, null, false).getItems();
        ItemResponse graphItem = graphItems.stream()
                .filter(item -> "completed-item".equals(item.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals("completed-chute", graphItem.getLocationId());
        assertEquals(RoutingStatus.COMPLETED, graphItem.getRoutingStatus());

        eventProcessor.processEventWithoutBroadcast(new ChuteEmptyEvent("completed-chute", arrivedAt.plusSeconds(1)));

        assertEquals(0L, liveLocationRepository.getItemCount("completed-chute"));
        assertNull(liveItemRepository.getItemState("completed-item"));
        assertTrue(graphService.getGraphData(arrivedAt.plusSeconds(1), false, null, false).getItems().stream()
                .noneMatch(item -> "completed-item".equals(item.getId())));
    }

    @Test
    void liveRoutingDecisionReroutePublishesPathAssignment() {
        Instant now = Instant.now();
        createLocation("assignment-reroute-decision", "Decision", LocationType.DECISION_POINT, 0);
        createLocation("assignment-reroute-preferred", "Preferred Chute", LocationType.CHUTE, 10);
        createLocation("assignment-reroute-alternate", "Alternate Chute", LocationType.CHUTE, 10);
        conveyorService.createConveyor("assignment-reroute-preferred-conveyor", "assignment-reroute-decision",
                "assignment-reroute-preferred", "Preferred", 10_000.0, 1.0, 0.0, false, true);
        conveyorService.createConveyor("assignment-reroute-alternate-conveyor", "assignment-reroute-decision",
                "assignment-reroute-alternate", "Alternate", 20_000.0, 1.0, 0.0, false, true);

        eventProcessor.processEvent(new ItemCreatedEvent(
                "assignment-reroute-item", "Reroute Item", 1.0, true, "assignment-reroute-decision",
                PositionType.LOCATION, 0.0,
                List.of("assignment-reroute-preferred", "assignment-reroute-alternate"),
                Map.of(), now));

        ItemPathAssignmentMessage initialAssignment = assertInstanceOf(ItemPathAssignmentMessage.class,
                amqpTemplate.receiveAndConvert("path-assignments", 2_000));
        assertEquals("assignment-reroute-item", initialAssignment.getItemId());
        assertEquals("assignment-reroute-preferred", initialAssignment.getFinalDestinationId());
        assertEquals(List.of("assignment-reroute-decision", "assignment-reroute-preferred"),
                initialAssignment.getPath());
        drainPathAssignmentsQueue();

        Instant rerouteTimestamp = now.plusSeconds(1);
        eventProcessor.processEvent(new ConnectionDeactivatedEvent("assignment-reroute-preferred-conveyor"));
        eventProcessor.processEvent(
                new ItemRoutingDecisionRequestedEvent("assignment-reroute-item", "assignment-reroute-decision",
                        rerouteTimestamp));

        ItemPathAssignmentMessage rerouteAssignment = assertInstanceOf(ItemPathAssignmentMessage.class,
                amqpTemplate.receiveAndConvert("path-assignments", 2_000));
        assertEquals(ItemPathAssignmentMessage.MESSAGE_TYPE, rerouteAssignment.getMessageType());
        assertEquals("assignment-reroute-item", rerouteAssignment.getItemId());
        assertEquals("assignment-reroute-alternate", rerouteAssignment.getFinalDestinationId());
        assertEquals(List.of("assignment-reroute-decision", "assignment-reroute-alternate"),
                rerouteAssignment.getPath());
        assertEquals(rerouteTimestamp, rerouteAssignment.getTimestamp());
    }

    @Test
    void reactivatedExitConveyorAtDecisionPointBecomesEligibleForLaterRouting() {
        Instant now = Instant.now();
        createLocation("reactivate-route-decision", "Decision", LocationType.DECISION_POINT, 0);
        createLocation("reactivate-route-preferred", "Preferred Chute", LocationType.CHUTE, 10);
        createLocation("reactivate-route-alternate", "Alternate Chute", LocationType.CHUTE, 10);
        conveyorService.createConveyor("reactivate-route-preferred-conveyor", "reactivate-route-decision",
                "reactivate-route-preferred", "Preferred", 10_000.0, 1.0, 0.0, false, true);
        conveyorService.createConveyor("reactivate-route-alternate-conveyor", "reactivate-route-decision",
                "reactivate-route-alternate", "Alternate", 20_000.0, 1.0, 0.0, false, true);

        eventProcessor.processEventWithoutBroadcast(new ConnectionDeactivatedEvent("reactivate-route-preferred-conveyor"));
        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                "reactivate-route-first", "First Item", 1.0, true, "reactivate-route-decision",
                PositionType.LOCATION, 0.0, List.of("reactivate-route-preferred", "reactivate-route-alternate"),
                Map.of(), now));

        var first = itemService.getItemById("reactivate-route-first");
        assertEquals("reactivate-route-alternate", first.getSelectedExitId());
        assertEquals(RoutingStatus.ASSIGNED, first.getRoutingStatus());

        eventProcessor.processEventWithoutBroadcast(new ConnectionActivatedEvent("reactivate-route-preferred-conveyor"));
        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                "reactivate-route-second", "Second Item", 1.0, true, "reactivate-route-decision",
                PositionType.LOCATION, 0.0, List.of("reactivate-route-preferred", "reactivate-route-alternate"),
                Map.of(), now.plusSeconds(1)));

        var second = itemService.getItemById("reactivate-route-second");
        assertEquals("reactivate-route-preferred", second.getSelectedExitId());
        assertEquals(List.of("reactivate-route-decision", "reactivate-route-preferred"), second.getPath());
        assertEquals(RoutingStatus.ASSIGNED, second.getRoutingStatus());
        assertNotNull(second.getRoutingStatusUpdatedAt());
    }

    @Test
    void destinationExitMappingsReplaceClearValidateAndStaySimulationIsolated() {
        destinationExitMappingService.saveMappings(new MapDestinationExitsEvent(List.of(
                new DestinationExitMappingRecord("live", List.of("exit-a", "exit-a", "exit-b")))));
        assertEquals(List.of("exit-a", "exit-b"), destinationExitMappingService.getExits("live"));

        assertThrows(IllegalArgumentException.class, () -> destinationExitMappingService.saveMappings(
                new MapDestinationExitsEvent(List.of(
                        new DestinationExitMappingRecord("duplicate", List.of("exit-a")),
                        new DestinationExitMappingRecord("duplicate", List.of("exit-b"))))));

        orientDBService.createInMemoryDatabase(SIMULATION_ID);
        try (var ignored = DatabaseContextHolder.enterSimulationContext(SIMULATION_ID)) {
            destinationExitMappingService.saveMappings(new MapDestinationExitsEvent(List.of(
                    new DestinationExitMappingRecord("live", List.of("sim-exit")))));
            assertEquals(List.of("sim-exit"), destinationExitMappingService.getExits("live"));
        }

        assertEquals(List.of("exit-a", "exit-b"), destinationExitMappingService.getExits("live"));
        destinationExitMappingService.saveMappings(new MapDestinationExitsEvent(List.of()));
        assertTrue(destinationExitMappingService.getMappings().isEmpty());
    }

    @Test
    void livePathCachePopulatesAndTopologyChangeInvalidatesCurrentNamespace() {
        Instant now = Instant.now();
        createLocation("cache-live-start", "Start", LocationType.JUNCTION, 0);
        createLocation("cache-live-exit", "Exit", LocationType.CHUTE, 10);
        conveyorService.createConveyor("cache-live-conveyor", "cache-live-start", "cache-live-exit",
                "Exit Conveyor", 10.0, 1.0, 0.0, false, true);

        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                "cache-live-item", "Cache Item", 1.0, true, "cache-live-start",
                PositionType.LOCATION, 0.0, List.of("cache-live-exit"), Map.of(), now));
        pathfindingService.calculateShortestPath("cache-live-start", PositionType.LOCATION, "cache-live-exit");

        assertTrue(redisTemplate.hasKey("pathcache:v1:available"));
        assertTrue(redisTemplate.hasKey("pathcache:v1:shortest"));

        eventProcessor.processEventWithoutBroadcast(new ConnectionLengthChangedEvent("cache-live-conveyor", 20.0));

        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey("pathcache:v1:available")));
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey("pathcache:v1:shortest")));
    }

    @Test
    void simulationPathCacheUsesSimulationNamespaceWithoutTouchingLiveCache() {
        Instant now = Instant.now();
        orientDBService.createInMemoryDatabase(SIMULATION_ID);

        try (var ignored = DatabaseContextHolder.enterSimulationContext(SIMULATION_ID)) {
            createLocation("cache-sim-start", "Start", LocationType.JUNCTION, 0);
            createLocation("cache-sim-exit", "Exit", LocationType.CHUTE, 10);
            conveyorService.createConveyor("cache-sim-conveyor", "cache-sim-start", "cache-sim-exit",
                    "Exit Conveyor", 10.0, 1.0, 0.0, false, true);

            eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                    "cache-sim-item", "Cache Item", 1.0, true, "cache-sim-start",
                    PositionType.LOCATION, 0.0, List.of("cache-sim-exit"), Map.of(), now));
            pathfindingService.calculateShortestPath("cache-sim-start", PositionType.LOCATION, "cache-sim-exit");

            assertTrue(redisTemplate.hasKey("sim:" + SIMULATION_ID + ":pathcache:v1:available"));
            assertTrue(redisTemplate.hasKey("sim:" + SIMULATION_ID + ":pathcache:v1:shortest"));
        }

        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey("pathcache:v1:available")));
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey("pathcache:v1:shortest")));
    }

    @Test
    void destroySimulationRemovesOnlyThatSimulationPathCache() {
        String destroyedSimulationId = "path-cache-destroyed";
        String otherSimulationId = "path-cache-other";

        redisTemplate.opsForHash().put("pathcache:v1:available", "live", "{}");
        redisTemplate.opsForHash().put("sim:" + destroyedSimulationId + ":pathcache:v1:available", "destroyed", "{}");
        redisTemplate.opsForHash().put("sim:" + destroyedSimulationId + ":pathcache:v1:shortest", "destroyed", "[]");
        redisTemplate.opsForHash().put("sim:" + otherSimulationId + ":pathcache:v1:available", "other", "{}");

        simulationService.destroySimulation(destroyedSimulationId);

        assertTrue(redisTemplate.hasKey("pathcache:v1:available"));
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(
                "sim:" + destroyedSimulationId + ":pathcache:v1:available")));
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(
                "sim:" + destroyedSimulationId + ":pathcache:v1:shortest")));
        assertTrue(redisTemplate.hasKey("sim:" + otherSimulationId + ":pathcache:v1:available"));
    }

    @Test
    void stagingReleaseSchedulesOneFifoBatchAndIsIdempotent() {
        createLocation("staging-source", "Source");
        createLocation("staging-junction", "Junction");
        createLocation("staging-target", "Target");
        conveyorService.createConveyor("staging", "staging-source", "staging-junction", "Staging",
                10.0, 1.0, null, true, true, ConveyorType.STAGING, 1, Map.of("lane", "A"));
        conveyorService.createConveyor("blocked-downstream", "staging-junction", "staging-target", "Downstream",
                10.0, 1.0, 0.0, true, false);

        Instant entry = timeService.physicalNow().plusMillis(100);
        eventProcessor.processEvent(new ItemCreatedEvent(
                "staged-head", "Head", 1.0, true, "staging", PositionType.CONVEYOR, 0.0, Map.of(), entry), false);
        eventProcessor.processEvent(new ItemCreatedEvent(
                "staged-tail", "Tail", 1.0, true, "staging", PositionType.CONVEYOR, 0.0, Map.of(),
                entry.plusMillis(10)), false);

        assertNull(itemMovementProcessor.getScheduledEvent("staged-head"));
        assertNull(itemMovementProcessor.getScheduledEvent("staged-tail"));
        assertEquals(ConveyorType.STAGING, conveyorService.getConveyorById("staging").getType());
        assertEquals(0.1, conveyorService.getConveyorById("staging").getMinDistance());
        assertEquals(Map.of("lane", "A"), conveyorService.getConveyorById("staging").getProperties());

        Instant releaseAt = entry.plusMillis(20);
        Map<String, Object> released = eventProcessor.processEvent(
                new ReleaseStagingConveyorEvent("staging", releaseAt), false);
        var headPlan = liveItemRepository.getItemState("staged-head");
        var tailPlan = liveItemRepository.getItemState("staged-tail");

        assertEquals(2, released.get("scheduledCount"));
        assertEquals(0, released.get("alreadyScheduledCount"));
        assertEquals("blocked-downstream", headPlan.getPlannedPositionId());
        assertEquals(PositionType.CONVEYOR, headPlan.getPlannedPositionType());
        assertEquals(headPlan.getPlannedTransitionTimestamp().plusMillis(100),
                tailPlan.getPlannedTransitionTimestamp());

        Map<String, Object> repeated = eventProcessor.processEvent(
                new ReleaseStagingConveyorEvent("staging", releaseAt.plusMillis(1)), false);
        assertEquals(0, repeated.get("scheduledCount"));
        assertEquals(2, repeated.get("alreadyScheduledCount"));
    }

    @Test
    void populatedConveyorTypeChangesCancelAndRebuildMovementSchedules() {
        createLocation("type-source", "Source");
        createLocation("type-junction", "Junction");
        createLocation("type-target", "Target");
        conveyorService.createConveyor("type-conveyor", "type-source", "type-junction", "Type Conveyor",
                100.0, 1.0, 0.5, true, true);
        conveyorService.createConveyor("type-downstream", "type-junction", "type-target", "Downstream",
                100.0, 1.0, 0.5, true, true);
        Instant entry = timeService.physicalNow().plusMillis(100);
        eventProcessor.processEvent(new ItemCreatedEvent(
                "type-item", "Item", 1.0, true, "type-conveyor", PositionType.CONVEYOR, 0.0, Map.of(), entry),
                false);
        assertNotNull(itemMovementProcessor.getScheduledEvent("type-item"));

        eventProcessor.processEvent(new ConnectionTypeChangedEvent(
                "type-conveyor", ConveyorType.STAGING, entry.plusSeconds(1)), false);
        assertNull(itemMovementProcessor.getScheduledEvent("type-item"));
        assertEquals(ConveyorType.STAGING, conveyorService.getConveyorById("type-conveyor").getType());

        eventProcessor.processEvent(new ConnectionTypeChangedEvent(
                "type-conveyor", ConveyorType.BELT, entry.plusSeconds(2)), false);
        assertNotNull(itemMovementProcessor.getScheduledEvent("type-item"));
        assertEquals(ConveyorType.BELT, conveyorService.getConveyorById("type-conveyor").getType());
    }

    private void createLocation(String id, String name) {
        createLocation(id, name, LocationType.GENERIC, 100);
    }

    private void createLocation(String id, String name, LocationType type, int capacity) {
        locationService.createLocation(new LocationInput(id, name, 0.0, 0.0, null, null, type, capacity,
                true, false, Map.of()));
    }

    private com.flunav.backend.domain.Item waitForPath(String itemId, List<String> expectedPath) {
        long deadline = System.currentTimeMillis() + 2_000;
        com.flunav.backend.domain.Item item = null;
        while (System.currentTimeMillis() < deadline) {
            item = itemService.getItemById(itemId);
            if (item != null && expectedPath.equals(item.getPath())) {
                return item;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertNotNull(item);
        assertEquals(expectedPath, item.getPath());
        return item;
    }

    private void createMappedCommandTopology(String startId, String destinationId) {
        createLocation(startId, "Start");
        createLocation(destinationId, "Destination");
        conveyorService.createConveyor(startId + "-to-" + destinationId, startId, destinationId,
                "Destination Conveyor", 10.0, 1.0, 0.0, true, true);
    }

    private void createItem(String id, String name, String locationId, Instant timestamp,
            Map<String, Object> properties) {
        createItem(id, name, locationId, timestamp, 0.0, properties);
    }

    private void createItem(String id, String name, String locationId, Instant timestamp, double priority,
            Map<String, Object> properties) {
        ItemInput item = new ItemInput();
        item.setId(id);
        item.setName(name);
        item.setActive(true);
        item.setPriority(priority);
        item.setLocationId(locationId);
        item.setPositionType(PositionType.LOCATION);
        item.setProperties(properties);
        item.setTimestamp(timestamp);
        itemService.createItem(item);
    }

    private DestinationMappingRecord mapping(String fieldName, DataType dataType, OperatorType operator, String value,
            String destination, Instant now) {
        return new DestinationMappingRecord(fieldName, dataType, operator, value, List.of(destination),
                now.minusSeconds(60),
                now.plusSeconds(3600));
    }

    private DestinationMappingRecord rushMapping(String fieldName, String value, String destination,
            Instant validFrom, Instant rushAt, Instant validTo) {
        return new DestinationMappingRecord(fieldName, DataType.STRING, OperatorType.EQUAL, value, null, null,
                List.of(destination), validFrom, rushAt, validTo);
    }

    private void drainCommandsQueue() {
        amqpAdmin.purgeQueue("commands", true);
    }

    private void drainPathAssignmentsQueue() {
        amqpAdmin.purgeQueue("path-assignments", true);
    }

    private void resetState() {
        DatabaseContextHolder.clearSimulation();

        try {
            Objects.requireNonNull(redisTemplate.getConnectionFactory())
                    .getConnection()
                    .serverCommands()
                    .flushAll();
        } catch (Exception ignored) {
        }

        try {
            orientDBService.resetMainDatabaseForTests(SIMULATION_ID);
        } catch (Exception ignored) {
        }

        DatabaseContextHolder.clearSimulation();
    }
}
