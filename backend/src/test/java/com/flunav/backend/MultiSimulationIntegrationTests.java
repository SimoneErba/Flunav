package com.flunav.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestConstructor;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.context.SimulationBuildCacheContext;
import com.flunav.backend.models.multisimulation.ArrivalConfiguration;
import com.flunav.backend.models.multisimulation.ArrivalDistribution;
import com.flunav.backend.models.multisimulation.DestinationProbability;
import com.flunav.backend.models.multisimulation.MultiSimulationConfiguration;
import com.flunav.backend.models.multisimulation.MultiSimulationStatus;
import com.flunav.backend.services.EventProcessor;
import com.flunav.backend.services.ItemService;
import com.flunav.backend.services.MultiSimulationService;
import com.flunav.backend.services.SimulationService;
import com.flunav.backend.services.TopologyProvider;
import com.flunav.backend.repositories.SimulationItemMetadataRepository;
import com.flunav.backend.repositories.LiveItemRepository;
import com.flunav.backend.repositories.support.MultiSimulationRuntimeStore;

import flunav.events.ConnectionDeactivatedEvent;
import flunav.events.ConnectionCreatedEvent;
import flunav.events.DestinationExitMappingRecord;
import flunav.events.LocationCreatedEvent;
import flunav.events.MapDestinationExitsEvent;
import flunav.events.ItemCreatedEvent;
import flunav.events.ItemPriorityUpdatedEvent;
import flunav.events.ItemPropertiesUpdatedEvent;
import flunav.types.ConveyorType;
import flunav.types.LocationType;
import flunav.types.PositionType;
import flunav.types.RoutingStatus;

@SpringBootTest(properties = {
        "springwolf.enabled=false",
        "state-recovery.enabled=false",
        "graph-snapshot.enabled=false",
        "metric-snapshot.enabled=false"
})
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class MultiSimulationIntegrationTests extends BaseIntegrationTest {
    private final EventProcessor eventProcessor;
    private final MultiSimulationService multiSimulationService;
    private final SimulationService simulationService;
    private final StringRedisTemplate redis;
    private final ItemService itemService;
    private final SimulationItemMetadataRepository itemMetadataCache;
    private final TopologyProvider topologyProvider;
    private final MultiSimulationRuntimeStore runtimeStore;
    private final LiveItemRepository liveItemRepository;

    MultiSimulationIntegrationTests(
            EventProcessor eventProcessor,
            MultiSimulationService multiSimulationService,
            SimulationService simulationService,
            StringRedisTemplate redis,
            ItemService itemService,
            SimulationItemMetadataRepository itemMetadataCache,
            TopologyProvider topologyProvider,
            MultiSimulationRuntimeStore runtimeStore,
            LiveItemRepository liveItemRepository) {
        this.eventProcessor = eventProcessor;
        this.multiSimulationService = multiSimulationService;
        this.simulationService = simulationService;
        this.redis = redis;
        this.itemService = itemService;
        this.itemMetadataCache = itemMetadataCache;
        this.topologyProvider = topologyProvider;
        this.runtimeStore = runtimeStore;
        this.liveItemRepository = liveItemRepository;
    }

    @Test
    void multiRuntimeMaintainsCapacityIndexesWithoutRedisScans() {
        String runtimeId = "capacity-index-run-" + System.nanoTime();
        var source = createRunnableMultiSimulation("Capacity index " + runtimeId, 1);
        Instant start = source.configuration().simulationStartTime();
        simulationService.createMultiSimulationRuntime(runtimeId, start, source.baseline());
        try (var context = DatabaseContextHolder.enterSimulationContext(runtimeId);
                var cache = SimulationBuildCacheContext.enterMultiRun(runtimeId)) {
            liveItemRepository.saveItemState("assigned", "source", PositionType.LOCATION, start, 0,
                    "Assigned", List.of("destination"), "exit", RoutingStatus.ASSIGNED, start, List.of());
            liveItemRepository.saveItemState("waiting", "source", PositionType.LOCATION, start, 0,
                    "Waiting", List.of("destination"), null, RoutingStatus.WAITING_FOR_CAPACITY, start, List.of());

            assertEquals(1, liveItemRepository.countItemsAssignedToExit("exit", null));
            assertEquals(0, liveItemRepository.countItemsAssignedToExit("exit", "assigned"));
            assertEquals(List.of("waiting"), liveItemRepository.getItemsWaitingForCapacity().stream()
                    .map(item -> item.getId()).toList());

            liveItemRepository.updatePosition("assigned", "exit", PositionType.LOCATION, start.plusSeconds(1),
                    0, List.of());
            liveItemRepository.updateRoutingStatus("waiting", RoutingStatus.ASSIGNED, start.plusSeconds(1));
            assertEquals(0, liveItemRepository.countItemsAssignedToExit("exit", null));
            assertTrue(liveItemRepository.getItemsWaitingForCapacity().isEmpty());
            assertTrue(redis.keys("sim:" + runtimeId + ":*").isEmpty());
        } finally {
            simulationService.destroySimulation(runtimeId);
        }
    }

    @Test
    void metadataAndTopologyCachesFollowReducedEvents() {
        String runtimeId = "cache-run-" + System.nanoTime();
        var source = createRunnableMultiSimulation("Cache verification " + runtimeId, 1);
        String sourceId = source.configuration().sourceLocationId();
        String destination = source.configuration().destinations().getFirst().destination();
        String conveyorId = source.baseline().graph().getConveyors().stream()
                .filter(conveyor -> sourceId.equals(conveyor.getSourceId()))
                .findFirst().orElseThrow().getId();
        String itemId = runtimeId + "-item";
        Instant start = source.configuration().simulationStartTime();

        simulationService.createMultiSimulationRuntime(runtimeId, start, source.baseline());
        try (var context = DatabaseContextHolder.enterSimulationContext(runtimeId);
                var cache = SimulationBuildCacheContext.enterMultiRun(runtimeId)) {
            eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(itemId, itemId, 1.0, 0.2,
                    true, sourceId, PositionType.LOCATION, 0.0, List.of(destination),
                    java.util.Map.of("batch", "first"), start));
            assertTrue(itemMetadataCache.get(itemId).isPresent());
            assertEquals(0.2, itemService.getItemById(itemId).getPriority());

            eventProcessor.processEventWithoutBroadcast(new ItemPriorityUpdatedEvent(itemId, 0.8,
                    start.plusSeconds(1)));
            eventProcessor.processEventWithoutBroadcast(new ItemPropertiesUpdatedEvent(itemId,
                    java.util.Map.of("batch", "second")));
            assertEquals(0.8, itemMetadataCache.get(itemId).orElseThrow().getPriority());
            assertEquals("second", itemService.getItemById(itemId).getProperties().get("batch"));
            try (var bypass = SimulationBuildCacheContext.enter("other-simulation")) {
                assertEquals("second", itemService.getItemById(itemId).getProperties().get("batch"));
            }

            assertTrue(topologyProvider.getConveyorById(conveyorId).isActive());
            eventProcessor.processEventWithoutBroadcast(new ConnectionDeactivatedEvent(conveyorId,
                    start.plusSeconds(2)));
            assertFalse(topologyProvider.getConveyorById(conveyorId).isActive());
        } finally {
            simulationService.destroySimulation(runtimeId);
        }
        assertFalse(Boolean.TRUE.equals(redis.hasKey("sim:" + runtimeId + ":build_item_metadata")));
    }

    @Test
    void cleanupDoesNotDestroyAnActiveMultiSimulationRuntime() {
        String runtimeId = "active-multi-runtime-" + System.nanoTime();
        var source = createRunnableMultiSimulation("Cleanup protection " + runtimeId, 1);

        simulationService.createMultiSimulationRuntime(
                runtimeId, source.configuration().simulationStartTime(), source.baseline());
        try {
            simulationService.cleanupAbandonedSimulations();
            assertTrue(runtimeStore.contains(runtimeId));
            assertEquals(runtimeId, simulationService.getSimulationState(runtimeId).getId());
        } finally {
            simulationService.destroySimulation(runtimeId);
        }
        assertFalse(runtimeStore.contains(runtimeId));
    }

    private com.flunav.backend.models.multisimulation.MultiSimulation createRunnableMultiSimulation(
            String name,
            int runs) {
        String suffix = String.valueOf(System.nanoTime());
        String source = "multi-source-" + suffix;
        String chute = "multi-chute-" + suffix;
        String conveyor = "multi-conveyor-" + suffix;
        String destination = "multi-destination-" + suffix;

        eventProcessor.process(new LocationCreatedEvent(
                source, "Source", true, 0.0, 0.0, LocationType.GENERIC, 100, new HashMap<>()), false).join();
        eventProcessor.process(new LocationCreatedEvent(
                chute, "Chute", true, 1.0, 0.0, LocationType.CHUTE, 100, new HashMap<>()), false).join();
        eventProcessor.process(new ConnectionCreatedEvent(
                conveyor, source, chute, 1.0, 1.0, 0.0, null, true,
                "Conveyor", true, ConveyorType.BELT, 100, new HashMap<>()), false).join();
        eventProcessor.process(new MapDestinationExitsEvent(List.of(
                new DestinationExitMappingRecord(destination, List.of(chute)))), false).join();

        return multiSimulationService.create(new MultiSimulationConfiguration(
                name,
                60,
                runs,
                new ArrivalConfiguration(360, ArrivalDistribution.FIXED, 0),
                source,
                List.of(new DestinationProbability(destination, 1.0)),
                List.of(),
                1234L,
                Instant.parse("2035-01-01T00:00:00Z")));
    }

    @Test
    void runsWithDirectChuteDestinationWithoutExitMapping() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String source = "direct-source-" + suffix;
        String chute = "direct-chute-" + suffix;
        String conveyor = "direct-conveyor-" + suffix;

        eventProcessor.process(new LocationCreatedEvent(
                source, "Source", true, 0.0, 0.0, LocationType.GENERIC, 100, new HashMap<>()), false).join();
        eventProcessor.process(new LocationCreatedEvent(
                chute, "Chute", true, 1.0, 0.0, LocationType.CHUTE, 100, new HashMap<>()), false).join();
        eventProcessor.process(new ConnectionCreatedEvent(
                conveyor, source, chute, 1.0, 1.0, 0.0, null, true,
                "Conveyor", true, ConveyorType.BELT, 100, new HashMap<>()), false).join();

        var created = multiSimulationService.create(new MultiSimulationConfiguration(
                "Direct chute destination",
                60,
                1,
                new ArrivalConfiguration(360, ArrivalDistribution.FIXED, 0),
                source,
                List.of(new DestinationProbability(chute, 1.0)),
                List.of(),
                1234L,
                Instant.parse("2035-01-01T00:00:00Z")));
        multiSimulationService.start(created.id());
        waitForCompletion(created.id(), Duration.ofSeconds(30));

        assertEquals(MultiSimulationStatus.COMPLETED, multiSimulationService.get(created.id()).status());
        assertEquals(5, multiSimulationService.runs(created.id()).getFirst().metrics().itemsCompleted());
    }

    @Test
    void runsTheSharedMovementEngineAndCleansEveryTemporaryNamespace() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String source = "multi-source-" + suffix;
        String chute = "multi-chute-" + suffix;
        String conveyor = "multi-conveyor-" + suffix;
        String destination = "multi-destination-" + suffix;

        eventProcessor.process(new LocationCreatedEvent(
                source, "Source", true, 0.0, 0.0, LocationType.GENERIC, 100, new HashMap<>()), false).join();
        eventProcessor.process(new LocationCreatedEvent(
                chute, "Chute", true, 1.0, 0.0, LocationType.CHUTE, 100, new HashMap<>()), false).join();
        eventProcessor.process(new ConnectionCreatedEvent(
                conveyor, source, chute, 1.0, 1.0, 0.0, null, true,
                "Conveyor", true, ConveyorType.BELT, 100, new HashMap<>()), false).join();
        eventProcessor.process(new MapDestinationExitsEvent(List.of(
                new DestinationExitMappingRecord(destination, List.of(chute)))), false).join();

        MultiSimulationConfiguration configuration = new MultiSimulationConfiguration(
                "Integration multi-simulation",
                60,
                2,
                new ArrivalConfiguration(360, ArrivalDistribution.FIXED, 0),
                source,
                List.of(new DestinationProbability(destination, 1.0)),
                List.of(),
                1234L,
                Instant.parse("2035-01-01T00:00:00Z"));

        var created = multiSimulationService.create(configuration);
        multiSimulationService.start(created.id());
        waitForCompletion(created.id(), Duration.ofSeconds(30));

        var completed = multiSimulationService.get(created.id());
        assertEquals(MultiSimulationStatus.COMPLETED, completed.status());
        assertEquals(2, completed.completedRuns());
        assertEquals(0, completed.failedRuns());
        var runs = multiSimulationService.runs(created.id());
        assertEquals(2, runs.size());
        assertTrue(runs.stream().allMatch(run -> run.metrics() != null && run.metrics().itemsGenerated() == 6));
        assertTrue(runs.stream().allMatch(run -> run.metrics().itemsCompleted() == 5));
        assertTrue(runs.stream().allMatch(run -> run.metrics().itemsRemaining() == 1));
        assertNotNull(multiSimulationService.report(created.id()));

        for (int runIndex = 0; runIndex < 2; runIndex++) {
            String runtimeId = created.id() + "_run_" + runIndex;
            assertFalse(Boolean.TRUE.equals(redis.hasKey("sim:" + runtimeId + ":state")));
            assertTrue(redis.keys("sim:" + runtimeId + ":*").isEmpty());
        }
    }

    @Test
    void highArrivalRunsWithTheSameSeedProduceIdenticalMetrics() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String source = "busy-source-" + suffix;
        String chute = "busy-chute-" + suffix;
        String conveyor = "busy-conveyor-" + suffix;
        String destination = "busy-destination-" + suffix;

        eventProcessor.process(new LocationCreatedEvent(
                source, "Busy Source", true, 0.0, 0.0, LocationType.GENERIC, 100, new HashMap<>()), false).join();
        eventProcessor.process(new LocationCreatedEvent(
                chute, "Busy Chute", true, 1.0, 0.0, LocationType.CHUTE, 100, new HashMap<>()), false).join();
        eventProcessor.process(new ConnectionCreatedEvent(
                conveyor, source, chute, 100.0, 1.0, 0.0, null, true,
                "Busy Conveyor", true, ConveyorType.BELT, 100, new HashMap<>()), false).join();
        eventProcessor.process(new MapDestinationExitsEvent(List.of(
                new DestinationExitMappingRecord(destination, List.of(chute)))), false).join();

        MultiSimulationConfiguration configuration = new MultiSimulationConfiguration(
                "High arrival deterministic run",
                30,
                1,
                new ArrivalConfiguration(3600, ArrivalDistribution.FIXED, 0),
                source,
                List.of(new DestinationProbability(destination, 1.0)),
                List.of(),
                1234L,
                Instant.parse("2035-01-01T00:00:00Z"));

        var first = multiSimulationService.create(configuration);
        multiSimulationService.start(first.id());
        waitForCompletion(first.id(), Duration.ofMinutes(3));
        var second = multiSimulationService.create(configuration);
        multiSimulationService.start(second.id());
        waitForCompletion(second.id(), Duration.ofMinutes(3));

        assertEquals(MultiSimulationStatus.COMPLETED, multiSimulationService.get(first.id()).status());
        assertEquals(MultiSimulationStatus.COMPLETED, multiSimulationService.get(second.id()).status());
        var firstRun = multiSimulationService.runs(first.id()).getFirst();
        var secondRun = multiSimulationService.runs(second.id()).getFirst();
        assertNotNull(firstRun.metrics());
        assertEquals(30, firstRun.metrics().itemsGenerated());
        assertEquals(30, firstRun.metrics().itemsRemaining());
        assertEquals(30, firstRun.metrics().maximumSystemPopulation());
        assertEquals(firstRun.metrics(), secondRun.metrics());
    }

    private void waitForCompletion(String id, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            MultiSimulationStatus status = multiSimulationService.get(id).status();
            if (status == MultiSimulationStatus.COMPLETED
                    || status == MultiSimulationStatus.COMPLETED_WITH_FAILURES
                    || status == MultiSimulationStatus.CANCELLED
                    || status == MultiSimulationStatus.FAILED) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Multi-simulation did not finish before timeout");
    }
}
