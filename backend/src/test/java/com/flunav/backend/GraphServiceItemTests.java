package com.flunav.backend;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.input.ItemInput;
import com.flunav.backend.models.input.LocationInput;
import com.flunav.backend.models.response.ItemResponse;
import com.flunav.backend.repositories.LiveItemRepository;
import com.flunav.backend.services.ConveyorService;
import com.flunav.backend.services.DestinationMappingService;
import com.flunav.backend.services.EventProcessor;
import com.flunav.backend.services.GraphService;
import com.flunav.backend.services.ItemService;
import com.flunav.backend.services.OrientDBService;
import com.flunav.backend.services.StaleItemCleanupService;
import flunav.events.DestinationMappingRecord;
import flunav.events.ItemCreatedEvent;
import flunav.events.ItemDestinationEvent;
import flunav.events.MapDestinationsEvent;
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
    private final StaleItemCleanupService staleItemCleanupService;
    private final EventProcessor eventProcessor;
    private final DestinationMappingService destinationMappingService;
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
            StaleItemCleanupService staleItemCleanupService,
            EventProcessor eventProcessor,
            DestinationMappingService destinationMappingService,
            com.flunav.backend.services.LocationService locationService,
            ConveyorService conveyorService,
            OrientDBService orientDBService,
            StringRedisTemplate redisTemplate,
            AmqpTemplate amqpTemplate,
            AmqpAdmin amqpAdmin) {
        this.graphService = graphService;
        this.itemService = itemService;
        this.liveItemRepository = liveItemRepository;
        this.staleItemCleanupService = staleItemCleanupService;
        this.eventProcessor = eventProcessor;
        this.destinationMappingService = destinationMappingService;
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
                0.0, "Live Stale", null, null);
        liveItemRepository.saveItemState("live-fresh", "live-start", PositionType.LOCATION, now.minusSeconds(23 * 3600),
                0.0, "Live Fresh", null, null);

        orientDBService.createInMemoryDatabase(SIMULATION_ID);
        try (var ignored = DatabaseContextHolder.enterSimulationContext(SIMULATION_ID)) {
            liveItemRepository.saveItemState("sim-stale", "sim-start", PositionType.LOCATION,
                    now.minusSeconds(30 * 3600),
                    0.0, "Simulation Stale", null, null);
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
                now.minusSeconds(30), 0.0, "Live Invalid", null, null);

        orientDBService.createInMemoryDatabase(SIMULATION_ID);
        try (var ignored = DatabaseContextHolder.enterSimulationContext(SIMULATION_ID)) {
            liveItemRepository.saveItemState("sim-invalid", "missing-sim-location", PositionType.LOCATION,
                    now.minusSeconds(30), 0.0, "Simulation Invalid", null, null);
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
                new DestinationMappingRecord("123", "mapped-exit-a", now.minusSeconds(60), now.plusSeconds(3600)))));
        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent("item-mapped", "Mapped Item", 1.0, true,
                "mapped-start", PositionType.LOCATION, 0.0, Map.of("flight_number", "123"), now));

        var item = itemService.getItemById("item-mapped");

        assertNotNull(item);
        assertEquals("mapped-exit-a", item.getDestinationId());
        assertEquals(List.of("mapped-start", "mapped-exit-a"), item.getPath());
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
                new DestinationMappingRecord("123", "explicit-exit-a", now.minusSeconds(60), now.plusSeconds(3600)))));
        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent("item-explicit", "Explicit Item", 1.0, true,
                "explicit-start", PositionType.LOCATION, 0.0, "explicit-exit-b", Map.of("flight_number", "123"), now));

        var item = itemService.getItemById("item-explicit");

        assertNotNull(item);
        assertEquals("explicit-exit-b", item.getDestinationId());
        assertEquals(List.of("explicit-start", "explicit-exit-b"), item.getPath());
    }

    @Test
    void liveMappedItemCreationPublishesDestinationCommand() {
        Instant now = Instant.now();
        createMappedCommandTopology("command-start", "command-exit");

        eventProcessor.processEventWithoutBroadcast(new MapDestinationsEvent("flight_number", List.of(
                new DestinationMappingRecord("123", "command-exit", now.minusSeconds(60), now.plusSeconds(3600)))));
        eventProcessor.processEvent(new ItemCreatedEvent("item-command", "Command Item", 1.0, true,
                "command-start", PositionType.LOCATION, 0.0, Map.of("flight_number", "123"), now), true);

        var item = itemService.getItemById("item-command");
        assertNotNull(item);
        assertEquals("command-exit", item.getDestinationId());

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
                new DestinationMappingRecord("123", "no-match-exit", now.minusSeconds(60), now.plusSeconds(3600)))));
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
                new DestinationMappingRecord("123", "explicit-command-exit", now.minusSeconds(60),
                        now.plusSeconds(3600)))));
        eventProcessor.processEvent(new ItemCreatedEvent("item-explicit-command", "Explicit Command Item", 1.0, true,
                "explicit-command-start", PositionType.LOCATION, 0.0, "explicit-command-other-exit",
                Map.of("flight_number", "123"), now), true);

        assertNull(amqpTemplate.receiveAndConvert("commands", 300));
    }

    @Test
    void processEventWithoutBroadcastDoesNotPublishDestinationCommand() {
        Instant now = Instant.now();
        createMappedCommandTopology("without-broadcast-start", "without-broadcast-exit");

        eventProcessor.processEventWithoutBroadcast(new MapDestinationsEvent("flight_number", List.of(
                new DestinationMappingRecord("123", "without-broadcast-exit", now.minusSeconds(60),
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
                    new DestinationMappingRecord("123", "sim-command-exit", now.minusSeconds(60),
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

        assertTrue(destinationMappingService.resolveDestination(Map.of("flight", "KL123"), now).isEmpty());
        assertEquals("replace-destination-b",
                destinationMappingService.resolveDestination(Map.of("flight", "LH456"), now).orElseThrow());

        destinationMappingService.saveMapDestinations(new MapDestinationsEvent(null, List.of()));

        assertTrue(destinationMappingService.resolveDestination(Map.of("flight", "LH456"), now).isEmpty());
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

        assertEquals("mixed-destination-a",
                destinationMappingService.resolveDestination(Map.of("flight", "KL123"), now).orElseThrow());
        assertEquals("mixed-destination-b",
                destinationMappingService.resolveDestination(Map.of("priority", 11), now).orElseThrow());

        destinationMappingService.saveMapDestinations(new MapDestinationsEvent("legacyField", List.of(
                new DestinationMappingRecord("legacy-value", "legacy-destination", now.minusSeconds(60),
                        now.plusSeconds(3600)))));

        assertEquals("legacy-destination",
                destinationMappingService.resolveDestination(Map.of("legacyField", "legacy-value"), now).orElseThrow());
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
                                "valid-destination", now.minusSeconds(60), now.plusSeconds(3600))))));

        assertThrows(IllegalArgumentException.class, () -> destinationMappingService.saveMapDestinations(
                new MapDestinationsEvent(null, List.of(
                        new DestinationMappingRecord("flight", DataType.STRING, OperatorType.EQUAL, "",
                                "valid-destination", now.minusSeconds(60), now.plusSeconds(3600))))));

        assertThrows(IllegalArgumentException.class, () -> destinationMappingService.saveMapDestinations(
                new MapDestinationsEvent(null, List.of(
                        new DestinationMappingRecord("flight", DataType.STRING, OperatorType.EQUAL, "KL123",
                                "valid-destination", now.plusSeconds(3600), now.minusSeconds(60))))));
    }

    @Test
    void destinationMappingsCanReferenceDestinationsThatDoNotExistYet() {
        Instant now = Instant.now();

        destinationMappingService.saveMapDestinations(
                new MapDestinationsEvent(null, List.of(
                        mapping("flight", DataType.STRING, OperatorType.EQUAL, "KL123", "missing-location", now))));

        assertEquals("missing-location",
                destinationMappingService.resolveDestination(Map.of("flight", "KL123"), now).orElseThrow());
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

        assertEquals("operator-destination-a",
                destinationMappingService.resolveDestination(Map.of("weight", "10.5"), now).orElseThrow());
        assertEquals("operator-destination-b",
                destinationMappingService.resolveDestination(Map.of("cutoff", "2025-12-31T23:59:59Z"), now)
                        .orElseThrow());
        assertEquals("operator-destination-c",
                destinationMappingService.resolveDestination(Map.of("fragile", "true"), now).orElseThrow());
        assertTrue(destinationMappingService.resolveDestination(Map.of("weight", "9"), now).isEmpty());
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

            assertEquals("sim-destination",
                    destinationMappingService.resolveDestination(Map.of("flight", "KL123"), now).orElseThrow());
        }

        assertEquals("live-destination",
                destinationMappingService.resolveDestination(Map.of("flight", "KL123"), now).orElseThrow());
    }

    private void createLocation(String id, String name) {
        locationService.createLocation(new LocationInput(id, name, 0.0, 0.0, null, null, LocationType.GENERIC, 100,
                true, false, Map.of()));
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
        return new DestinationMappingRecord(fieldName, dataType, operator, value, destination, now.minusSeconds(60),
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
