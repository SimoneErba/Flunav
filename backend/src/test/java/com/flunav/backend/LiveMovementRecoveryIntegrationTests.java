package com.flunav.backend;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.input.ItemInput;
import com.flunav.backend.models.input.LocationInput;
import com.flunav.backend.repositories.LiveConveyorRepository;
import com.flunav.backend.repositories.LiveItemRepository;
import com.flunav.backend.services.ConveyorService;
import com.flunav.backend.services.ItemService;
import com.flunav.backend.services.LiveMovementRecoveryService;
import com.flunav.backend.services.LiveSystemScheduler;
import com.flunav.backend.services.LocationService;
import com.flunav.backend.services.OrientDBService;
import com.flunav.backend.services.TimeService;
import flunav.events.ItemPositionChangedEvent;
import flunav.types.LocationType;
import flunav.types.PositionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestConstructor;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "springwolf.enabled=false",
        "state-recovery.enabled=false",
        "stale-item-cleanup.enabled=false",
        "graph-snapshot.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false"
})
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class LiveMovementRecoveryIntegrationTests extends BaseIntegrationTest {
    private final LiveMovementRecoveryService recoveryService;
    private final LiveSystemScheduler liveSystemScheduler;
    private final LiveItemRepository liveItemRepository;
    private final LiveConveyorRepository liveConveyorRepository;
    private final LocationService locationService;
    private final ConveyorService conveyorService;
    private final ItemService itemService;
    private final OrientDBService orientDBService;
    private final TimeService timeService;
    private final StringRedisTemplate redisTemplate;

    LiveMovementRecoveryIntegrationTests(
            LiveMovementRecoveryService recoveryService,
            LiveSystemScheduler liveSystemScheduler,
            LiveItemRepository liveItemRepository,
            LiveConveyorRepository liveConveyorRepository,
            LocationService locationService,
            ConveyorService conveyorService,
            ItemService itemService,
            OrientDBService orientDBService,
            TimeService timeService,
            StringRedisTemplate redisTemplate) {
        this.recoveryService = recoveryService;
        this.liveSystemScheduler = liveSystemScheduler;
        this.liveItemRepository = liveItemRepository;
        this.liveConveyorRepository = liveConveyorRepository;
        this.locationService = locationService;
        this.conveyorService = conveyorService;
        this.itemService = itemService;
        this.orientDBService = orientDBService;
        this.timeService = timeService;
        this.redisTemplate = redisTemplate;
    }

    @BeforeEach
    void setup() {
        resetState();
    }

    @AfterEach
    void cleanup() {
        liveSystemScheduler.cancelAll();
        resetState();
    }

    @Test
    void recoveryCheckpointsCurrentPositionAndSchedulesRemainingMovement() {
        Instant recoveryTime = Instant.parse("2026-06-07T10:00:00Z");
        timeService.useFixedClock(recoveryTime);
        createTwoConveyorTopology("checkpoint");
        createItemOnConveyor("checkpoint-item", "checkpoint-first", recoveryTime.minusSeconds(2), 20.0);

        recoveryService.recoverLiveMovementSchedules();

        var itemState = liveItemRepository.getItemState("checkpoint-item");
        assertNotNull(itemState);
        assertEquals(recoveryTime, itemState.getEntryTime());
        assertEquals(4.0, itemState.getAccumulatedDistance(), 0.001);
        assertTrue(liveConveyorRepository.getItemsOrderedByDistance("checkpoint-first")
                .contains("checkpoint-item"));

        var scheduledEvent = assertInstanceOf(ItemPositionChangedEvent.class,
                liveSystemScheduler.getScheduledEvent("checkpoint-item"));
        assertEquals("checkpoint-second", scheduledEvent.getLocationId());
        assertEquals(recoveryTime.plusSeconds(6), scheduledEvent.getTimestamp());
    }

    @Test
    void backendStartupAdvancesPersistedItemAndQueuesItsNextLiveEvent() throws Exception {
        Instant startupTime = Instant.parse("2026-06-07T11:00:00Z");
        timeService.useFixedClock(startupTime);

        createLocation("START", LocationType.JUNCTION);
        createLocation("MIDDLE", LocationType.JUNCTION);
        createLocation("D", LocationType.CHUTE);
        conveyorService.createConveyor("B", "START", "MIDDLE",
                "B", 10.0, 1.0, 0.0, true, true);
        conveyorService.createConveyor("C", "MIDDLE", "D",
                "C", 10.0, 1.0, 0.0, true, true);
        createItemOnConveyor("A", "B", startupTime.minusSeconds(12), 0.0);

        assertNotNull(itemService.getItemById("A"));
        assertEquals("B", liveItemRepository.getItemState("A").getPositionId());
        recoveryService.recoverOnStartup();

        waitUntil(() -> {
            var state = liveItemRepository.getItemState("A");
            return state != null
                    && "C".equals(state.getPositionId())
                    && liveSystemScheduler.getScheduledEvent("A") != null;
        });

        var itemState = liveItemRepository.getItemState("A");
        assertNotNull(itemState);
        assertEquals("C", itemState.getPositionId());
        assertEquals(startupTime.minusSeconds(2), itemState.getEntryTime());
        assertTrue(liveConveyorRepository.getItemsOrderedByDistance("C").contains("A"));
        assertFalse(liveConveyorRepository.getItemsOrderedByDistance("B").contains("A"));

        var scheduledEvent = assertInstanceOf(ItemPositionChangedEvent.class,
                liveSystemScheduler.getScheduledEvent("A"));
        assertEquals("A", scheduledEvent.getEntityId());
        assertEquals("D", scheduledEvent.getLocationId());
        assertEquals(startupTime.plusSeconds(8), scheduledEvent.getTimestamp());
    }

    private void createTwoConveyorTopology(String prefix) {
        createLocation(prefix + "-start", LocationType.JUNCTION);
        createLocation(prefix + "-middle", LocationType.JUNCTION);
        createLocation(prefix + "-exit", LocationType.CHUTE);
        conveyorService.createConveyor(prefix + "-first", prefix + "-start", prefix + "-middle",
                "First", 10.0, 1.0, 0.0, true, true);
        conveyorService.createConveyor(prefix + "-second", prefix + "-middle", prefix + "-exit",
                "Second", 10.0, 1.0, 0.0, true, true);
    }

    private void createLocation(String id, LocationType type) {
        locationService.createLocation(new LocationInput(
                id, id, 0.0, 0.0, null, null, type, 100, true, false, Map.of()));
    }

    private void createItemOnConveyor(String itemId, String conveyorId, Instant timestamp, double progress) {
        ItemInput item = new ItemInput();
        item.setId(itemId);
        item.setName(itemId);
        item.setActive(true);
        item.setLocationId(conveyorId);
        item.setPositionType(PositionType.CONVEYOR);
        item.setProgress(progress);
        item.setTimestamp(timestamp);
        item.setProperties(Map.of());
        itemService.createItem(item);
    }

    private void waitUntil(CheckedCondition condition) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.evaluate()) {
                return;
            }
            Thread.sleep(25);
        }
        assertTrue(condition.evaluate(), "Condition was not met before timeout");
    }

    private void resetState() {
        DatabaseContextHolder.clearSimulation();
        timeService.reset();
        liveSystemScheduler.cancelInternalEvent("checkpoint-item");
        liveSystemScheduler.cancelInternalEvent("overdue-item");
        liveSystemScheduler.cancelInternalEvent("A");

        try {
            Objects.requireNonNull(redisTemplate.getConnectionFactory())
                    .getConnection()
                    .serverCommands()
                    .flushAll();
        } catch (Exception ignored) {
        }

        try {
            orientDBService.resetMainDatabaseForTests("live-movement-recovery");
        } catch (Exception ignored) {
        }

        DatabaseContextHolder.clearSimulation();
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean evaluate() throws Exception;
    }
}
