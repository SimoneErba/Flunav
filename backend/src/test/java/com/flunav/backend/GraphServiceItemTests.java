package com.flunav.backend;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.input.ItemInput;
import com.flunav.backend.models.input.LocationInput;
import com.flunav.backend.models.response.ItemResponse;
import com.flunav.backend.repositories.LiveItemRepository;
import com.flunav.backend.services.ConveyorService;
import com.flunav.backend.services.GraphService;
import com.flunav.backend.services.ItemService;
import com.flunav.backend.services.OrientDBService;
import com.flunav.backend.services.StaleItemCleanupService;
import flunav.types.LocationType;
import flunav.types.PositionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "springwolf.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
        "rabbitmq.routing-key.item-events=test-key",
        "state-recovery.enabled=false",
        "graph-snapshot.enabled=false"
})
class GraphServiceItemTests extends BaseIntegrationTest {

    private static final String SIMULATION_ID = "graph-service-test-sim";

    @Autowired
    private GraphService graphService;

    @Autowired
    private ItemService itemService;

    @Autowired
    private LiveItemRepository liveItemRepository;

    @Autowired
    private StaleItemCleanupService staleItemCleanupService;

    @Autowired
    private com.flunav.backend.services.LocationService locationService;

    @Autowired
    private ConveyorService conveyorService;

    @Autowired
    private OrientDBService orientDBService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockBean
    private org.springframework.amqp.core.AmqpTemplate amqpTemplate;

    @BeforeEach
    void setup() {
        resetState();
    }

    @AfterEach
    void cleanup() {
        resetState();
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

    private void createLocation(String id, String name) {
        locationService.createLocation(new LocationInput(id, name, 0.0, 0.0, null, null, LocationType.GENERIC, 100,
                true, false, Map.of()));
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
