package com.flunav.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.RedisLiveItem;
import com.flunav.backend.models.simulation.SimulationStatus;
import com.flunav.backend.repositories.LiveItemRepository;
import com.flunav.backend.repositories.LiveSimulationRepository;
import com.flunav.backend.services.EventProcessor;
import com.flunav.backend.services.OrientDBService;
import com.flunav.backend.services.SimulationService;
import flunav.events.ItemCreatedEvent;
import flunav.events.ItemPositionChangedEvent;
import flunav.events.LocationCreatedEvent;
import flunav.types.LocationType;
import flunav.types.PositionType;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestConstructor;
import org.springframework.util.StopWatch;

@SpringBootTest(properties = {
        "springwolf.enabled=false",
        "rabbitmq.routing-key.item-events=1",
        "stale-item-cleanup.enabled=false",
        "state-recovery.enabled=false",
        "graph-snapshot.enabled=false",
        "simulation.manage-logic=true"
})
@Tag("stress")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class StressIntegrationTests extends BaseIntegrationTest {

    private static final int SIMULATION_COUNT = 3;
    private static final int ITEMS_PER_SIMULATION = 180;
    private static final int ORDERING_EVENT_COUNT = 500;
    private static final Duration SIMULATION_STRESS_LIMIT = Duration.ofSeconds(12);
    private static final Duration ORDERING_STRESS_LIMIT = Duration.ofSeconds(15);
    private static final long HEAP_DELTA_LIMIT_BYTES = 192L * 1024L * 1024L;

    private final EventProcessor eventProcessor;
    private final LiveItemRepository liveItemRepository;
    private final LiveSimulationRepository liveSimulationRepository;
    private final SimulationService simulationService;
    private final OrientDBService orientDBService;
    private final StringRedisTemplate redisTemplate;

    private final List<String> createdSimulationIds = new ArrayList<>();

    StressIntegrationTests(
            EventProcessor eventProcessor,
            LiveItemRepository liveItemRepository,
            LiveSimulationRepository liveSimulationRepository,
            SimulationService simulationService,
            OrientDBService orientDBService,
            StringRedisTemplate redisTemplate) {
        this.eventProcessor = eventProcessor;
        this.liveItemRepository = liveItemRepository;
        this.liveSimulationRepository = liveSimulationRepository;
        this.simulationService = simulationService;
        this.orientDBService = orientDBService;
        this.redisTemplate = redisTemplate;
    }

    @BeforeEach
    void setUp() {
        System.clearProperty("simulation.id");
        DatabaseContextHolder.clearSimulation();
        flushRedis();
    }

    @AfterEach
    void tearDown() {
        for (String simulationId : createdSimulationIds) {
            try {
                simulationService.destroySimulation(simulationId);
            } catch (Exception ignored) {
            }
            try {
                orientDBService.dropDatabase(simulationId);
            } catch (Exception ignored) {
            }
        }
        createdSimulationIds.clear();
        System.clearProperty("simulation.id");
        DatabaseContextHolder.clearSimulation();
        flushRedis();
    }

    @Test
    void concurrentSimulationsKeepHotStateIsolatedUnderBurstLoad() throws Exception {
        Instant start = Instant.parse("2026-07-01T10:00:00Z");
        for (int index = 0; index < SIMULATION_COUNT; index++) {
            createReadySimulation("stress-sim-" + index, start);
        }

        long heapBefore = usedHeapAfterGc();
        StopWatch stopWatch = new StopWatch("simulation-isolation-stress");
        CountDownLatch ready = new CountDownLatch(SIMULATION_COUNT);
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(SIMULATION_COUNT);
        List<CompletableFuture<Void>> tasks = new ArrayList<>();

        stopWatch.start();
        try {
            for (int index = 0; index < SIMULATION_COUNT; index++) {
                String simulationId = "stress-sim-" + index;
                String expectedNamePrefix = "sim-" + index + "-";
                tasks.add(CompletableFuture.runAsync(() -> runSimulationBurst(
                        simulationId, expectedNamePrefix, start, ready, startGate), executor));
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS), "Simulation workers did not become ready in time");
            startGate.countDown();
            CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).get(20, TimeUnit.SECONDS);
        } finally {
            stopWatch.stop();
            executor.shutdownNow();
        }

        long heapAfter = usedHeapAfterGc();
        assertTrue(stopWatch.getTotalTimeMillis() < SIMULATION_STRESS_LIMIT.toMillis(),
                "Concurrent simulation stress test exceeded time budget: " + stopWatch.getTotalTimeMillis() + "ms");
        assertTrue(heapAfter - heapBefore < HEAP_DELTA_LIMIT_BYTES,
                "Concurrent simulation stress test retained too much heap: " + (heapAfter - heapBefore) + " bytes");

        for (int index = 0; index < SIMULATION_COUNT; index++) {
            String simulationId = "stress-sim-" + index;
            String expectedName = "sim-" + index + "-item-42";

            assertEquals(ITEMS_PER_SIMULATION, liveItemRepository.countActiveItems(simulationId));

            RedisLiveItem namespacedItem = liveItemRepository.getItemState("item-42", simulationId);
            assertNotNull(namespacedItem, "Expected hot state for item-42 in " + simulationId);
            assertEquals(expectedName, namespacedItem.getName());
            assertEquals("entry", namespacedItem.getPositionId());

            for (int other = 0; other < SIMULATION_COUNT; other++) {
                if (other == index) {
                    continue;
                }
                RedisLiveItem foreignState = liveItemRepository.getItemState("item-42", "stress-sim-" + other);
                assertNotNull(foreignState, "Expected item-42 to exist in every simulation namespace");
                assertTrue(!expectedName.equals(foreignState.getName()),
                        "Simulation namespaces should not overwrite each other's item state");
            }

            Set<String> itemKeys = redisTemplate.keys("sim:" + simulationId + ":item:*");
            assertEquals(ITEMS_PER_SIMULATION, itemKeys == null ? 0 : itemKeys.size());
        }

        assertEquals(0, liveItemRepository.countActiveItems(null), "Live namespace should stay empty during sim stress");
    }

    @Test
    void denseEventBurstPreservesPerEntityOrderWithoutLeakingProcessingState() throws Exception {
        String simulationId = "ordering-stress-sim";
        Instant start = Instant.parse("2026-07-01T11:00:00Z");
        createReadySimulation(simulationId, start);

        try (var ignored = DatabaseContextHolder.enterSimulationContext(simulationId)) {
            createLocation("zone-a");
            createLocation("zone-b");
            createLocation("zone-c");
            eventProcessor.process(new ItemCreatedEvent(
                    "ordered-item",
                    "ordered-item",
                    1.0,
                    true,
                    "zone-a",
                    PositionType.LOCATION,
                    0.0,
                    Map.of(),
                    start), false).join();
        }

        long heapBefore = usedHeapAfterGc();
        StopWatch stopWatch = new StopWatch("ordering-stress");
        int baselineProcessingFutures = processingFutureCount();
        stopWatch.start();

        List<String> sequence = List.of("zone-a", "zone-b", "zone-c");
        try (var ignored = DatabaseContextHolder.enterSimulationContext(simulationId)) {
            List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
            for (int index = 0; index < ORDERING_EVENT_COUNT; index++) {
                String targetLocation = sequence.get(index % sequence.size());
                futures.add(eventProcessor.process(new ItemPositionChangedEvent(
                        "ordered-item",
                        targetLocation,
                        0.0,
                        start.plusMillis(index + 1)), false));
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(15, TimeUnit.SECONDS);
        } finally {
            stopWatch.stop();
        }

        long heapAfter = usedHeapAfterGc();
        String expectedFinalLocation = sequence.get((ORDERING_EVENT_COUNT - 1) % sequence.size());
        RedisLiveItem finalState = liveItemRepository.getItemState("ordered-item", simulationId);

        assertNotNull(finalState);
        assertEquals(expectedFinalLocation, finalState.getPositionId());
        assertEquals(start.plusMillis(ORDERING_EVENT_COUNT), finalState.getEntryTime());
        assertEquals(1, liveItemRepository.countActiveItems(simulationId));
        waitForProcessingFuturesToDrain(baselineProcessingFutures, Duration.ofSeconds(2));
        assertEquals(baselineProcessingFutures, processingFutureCount(),
                "Per-entity future chains should return to the pre-burst baseline");

        Set<String> simulationKeys = redisTemplate.keys("sim:" + simulationId + ":*");
        assertNotNull(simulationKeys);
        assertTrue(simulationKeys.size() <= 6,
                "Dense ordering burst should not leave behind unbounded simulation hot state");
        assertTrue(stopWatch.getTotalTimeMillis() < ORDERING_STRESS_LIMIT.toMillis(),
                "Ordering stress test exceeded time budget: " + stopWatch.getTotalTimeMillis() + "ms");
        assertTrue(heapAfter - heapBefore < HEAP_DELTA_LIMIT_BYTES,
                "Ordering stress test retained too much heap: " + (heapAfter - heapBefore) + " bytes");
    }

    /**
     * Creates isolated simulation storage eagerly so the test can focus on event
     * routing and ordering instead of background historical build timing.
     */
    private void createReadySimulation(String simulationId, Instant timestamp) {
        orientDBService.createInMemoryDatabase(simulationId);
        liveSimulationRepository.saveState(new LiveSimulationRepository.SimulationMetadata(
                simulationId,
                timestamp,
                SimulationStatus.READY,
                timestamp,
                timestamp,
                1.0,
                100.0));
        createdSimulationIds.add(simulationId);
    }

    /**
     * Reuses identical entity ids across simulations so any ThreadLocal leak or
     * namespace mix-up becomes visible as overwritten Redis hot state.
     */
    private void runSimulationBurst(String simulationId, String expectedNamePrefix, Instant start,
            CountDownLatch ready, CountDownLatch startGate) {
        try (var ignored = DatabaseContextHolder.enterSimulationContext(simulationId)) {
            createLocation("entry");
            ready.countDown();
            assertTrue(startGate.await(5, TimeUnit.SECONDS), "Timed out waiting to start simulation burst");

            for (int index = 0; index < ITEMS_PER_SIMULATION; index++) {
                eventProcessor.process(new ItemCreatedEvent(
                        "item-" + index,
                        expectedNamePrefix + "item-" + index,
                        1.0,
                        true,
                        "entry",
                        PositionType.LOCATION,
                        0.0,
                        Map.of("simulation", simulationId),
                        start.plusMillis(index)), false).join();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Simulation burst interrupted", e);
        }
    }

    private void createLocation(String locationId) {
        eventProcessor.process(new LocationCreatedEvent(
                locationId,
                locationId,
                true,
                0.0,
                0.0,
                LocationType.GENERIC,
                100,
                Map.of()), false).join();
    }

    private void flushRedis() {
        try {
            redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to flush Redis for stress tests", e);
        }
    }

    private long usedHeapAfterGc() throws InterruptedException {
        System.gc();
        Thread.sleep(150);
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    /**
     * The async ordering map must return to zero after a burst so repeated load
     * tests do not accumulate completed futures and hide a memory regression.
     */
    @SuppressWarnings("unchecked")
    private int processingFutureCount() {
        try {
            Field field = EventProcessor.class.getDeclaredField("processingFutures");
            field.setAccessible(true);
            ConcurrentMap<String, CompletableFuture<Void>> futures =
                    (ConcurrentMap<String, CompletableFuture<Void>>) field.get(eventProcessor);
            return futures.size();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to inspect EventProcessor processing futures", e);
        }
    }

    private void waitForProcessingFuturesToDrain(int baselineCount, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (processingFutureCount() <= baselineCount) {
                return;
            }
            Thread.sleep(25);
        }
    }
}
