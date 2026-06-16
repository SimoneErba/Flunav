package com.flunav.backend;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.input.ItemInput;
import com.flunav.backend.models.input.LocationInput;
import com.flunav.backend.models.response.ItemResponse;
import com.flunav.backend.repositories.LiveItemRepository;
import com.flunav.backend.repositories.LiveLocationRepository;
import com.flunav.backend.services.ConveyorService;
import com.flunav.backend.services.DestinationMappingService;
import com.flunav.backend.services.DestinationExitMappingService;
import com.flunav.backend.services.EventProcessor;
import com.flunav.backend.services.GraphService;
import com.flunav.backend.services.ItemMovementProcessor;
import com.flunav.backend.services.ItemService;
import com.flunav.backend.services.OrientDBService;
import com.flunav.backend.services.StaleItemCleanupService;
import flunav.events.DestinationMappingRecord;
import flunav.events.DestinationExitMappingRecord;
import flunav.events.ItemCreatedEvent;
import flunav.events.ItemDestinationEvent;
import flunav.events.ItemPositionChangedEvent;
import flunav.events.MapDestinationsEvent;
import flunav.events.MapDestinationExitsEvent;
import flunav.types.DataType;
import flunav.types.LocationType;
import flunav.types.OperatorType;
import flunav.types.PositionType;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private final LiveItemRepository liveItemRepository;
    private final LiveLocationRepository liveLocationRepository;
    private final StaleItemCleanupService staleItemCleanupService;
    private final EventProcessor eventProcessor;
    private final ItemMovementProcessor itemMovementProcessor;
    private final DestinationMappingService destinationMappingService;
    private final DestinationExitMappingService destinationExitMappingService;
    private final com.flunav.backend.services.LocationService locationService;
    private final ConveyorService conveyorService;
    private final OrientDBService orientDBService;
    private final StringRedisTemplate redisTemplate;
    private final AmqpTemplate amqpTemplate;
    private final AmqpAdmin amqpAdmin;

    GraphServiceItemTests(
            GraphService graphService,
            ItemService itemService,
            LiveItemRepository liveItemRepository,
            LiveLocationRepository liveLocationRepository,
            StaleItemCleanupService staleItemCleanupService,
            EventProcessor eventProcessor,
            ItemMovementProcessor itemMovementProcessor,
            DestinationMappingService destinationMappingService,
            DestinationExitMappingService destinationExitMappingService,
            com.flunav.backend.services.LocationService locationService,
            ConveyorService conveyorService,
            OrientDBService orientDBService,
            StringRedisTemplate redisTemplate,
            AmqpTemplate amqpTemplate,
            AmqpAdmin amqpAdmin) {
        this.graphService = graphService;
        this.itemService = itemService;
        this.liveItemRepository = liveItemRepository;
        this.liveLocationRepository = liveLocationRepository;
        this.staleItemCleanupService = staleItemCleanupService;
        this.eventProcessor = eventProcessor;
        this.itemMovementProcessor = itemMovementProcessor;
        this.destinationMappingService = destinationMappingService;
        this.destinationExitMappingService = destinationExitMappingService;
        this.locationService = locationService;
        this.conveyorService = conveyorService;
        this.orientDBService = orientDBService;
        this.redisTemplate = redisTemplate;
        this.amqpTemplate = amqpTemplate;
        this.amqpAdmin = amqpAdmin;
    }

    @BeforeEach
    void setup() {
        resetState();
        drainCommandsQueue();
    }

    @AfterEach
    void cleanup() {
        resetState();
        drainCommandsQueue();
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
                destinationMappingService.resolveDestinations(Map.of("priority", 11), now));

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
    void destinationMappingOperatorAliasesMatchCanonicalOperators() {
        assertEquals(OperatorType.EQUAL, OperatorType.fromString("EQUALS"));
        assertEquals(OperatorType.GREATER, OperatorType.fromString("GREATER_THAN"));
        assertEquals(OperatorType.LESSER, OperatorType.fromString("LESS_THAN"));
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
                Map.of("priority", "NORMAL"), now));

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
                    Map.of("priority", "NORMAL"), now.plusMillis(index)));
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
                    Map.of("priority", "NORMAL"), now.plusMillis(index)));
        }
        assertEquals(0, liveLocationRepository.getItemCount("projected-chute"));

        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                "projected-item-2", "Projected Item 2", 1.0, true, "projected-start",
                PositionType.LOCATION, 0.0, List.of("projected-chute"),
                Map.of("priority", "NORMAL"), now.plusMillis(2)));

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
                "priority-normal", "Normal", 1.0, true, "priority-start",
                PositionType.LOCATION, 0.0, List.of("priority-chute"),
                Map.of("priority", "NORMAL"), now));
        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                "priority-high", "High", 1.0, true, "priority-start",
                PositionType.LOCATION, 0.0, List.of("priority-chute"),
                Map.of("priority", "HIGH"), now.plusMillis(1)));
        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                "priority-high-overflow", "High Overflow", 1.0, true, "priority-start",
                PositionType.LOCATION, 0.0, List.of("priority-chute"),
                Map.of("priority", "HIGH"), now.plusMillis(2)));

        assertNull(itemService.getItemById("priority-normal").getSelectedExitId());
        assertEquals("priority-chute", itemService.getItemById("priority-high").getSelectedExitId());
        assertNull(itemService.getItemById("priority-high-overflow").getSelectedExitId());
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

        createItem("guard-reserved", "Reserved", "guard-b", future, Map.of("priority", "NORMAL"));
        liveItemRepository.updatePosition("guard-reserved", "guard-bc", PositionType.CONVEYOR, future, 0.0, null);
        liveItemRepository.updateRouting("guard-reserved", List.of("guard-d"), "guard-d",
                List.of("guard-b", "guard-c", "guard-d"));

        createItem("guard-extra", "Extra", "guard-c", future, Map.of("priority", "NORMAL"));
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

        createItem("decision-normal", "Normal", "decision-point", now, Map.of("priority", "NORMAL"));
        liveItemRepository.updateRouting(
                "decision-normal", List.of("decision-chute"), null, null);
        eventProcessor.process(
                new ItemPositionChangedEvent("decision-normal", "decision-point", 0.0, now), true).join();

        var normalItem = waitForPath("decision-normal", List.of("decision-point", "decision-loop"));
        assertNull(normalItem.getSelectedExitId());
        assertNull(amqpTemplate.receiveAndConvert("commands", 300));

        createItem("decision-high", "High", "decision-point", now, Map.of("priority", "HIGH"));
        liveItemRepository.updateRouting(
                "decision-high", List.of("decision-chute"), null, null);
        eventProcessor.process(
                new ItemPositionChangedEvent("decision-high", "decision-point", 0.0, now), true).join();

        var highItem = waitForPath("decision-high", List.of("decision-point", "decision-chute"));
        assertEquals("decision-chute", highItem.getSelectedExitId());
        assertNull(amqpTemplate.receiveAndConvert("commands", 300));
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

        createItem("free-normal", "Normal", "free-decision", now, Map.of("priority", "NORMAL"));
        liveItemRepository.updateRouting("free-normal", List.of("free-destination"), null, null);
        eventProcessor.process(
                new ItemPositionChangedEvent("free-normal", "free-decision", 0.0, now), true).join();

        var normalItem = waitForPath("free-normal", List.of("free-decision", "free-open-chute"));
        assertEquals("free-open-chute", normalItem.getSelectedExitId());
        assertNull(amqpTemplate.receiveAndConvert("commands", 300));
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
        ItemInput item = new ItemInput();
        item.setId(id);
        item.setName(name);
        item.setActive(true);
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

    private void drainCommandsQueue() {
        amqpAdmin.purgeQueue("commands", true);
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
