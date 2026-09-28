package com.flunav.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestConstructor;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.input.ItemInput;
import com.flunav.backend.models.input.LocationInput;
import com.flunav.backend.models.response.ItemResponse;
import com.flunav.backend.services.ConveyorService;
import com.flunav.backend.services.EventProcessor;
import com.flunav.backend.services.GraphService;
import com.flunav.backend.services.ItemService;
import com.flunav.backend.services.LocationService;
import com.flunav.backend.services.OrientDBService;
import com.flunav.backend.services.SensorMappingService;

import flunav.events.ItemPositionChangedEvent;
import flunav.events.MapSensorMappingsEvent;
import flunav.events.SensorMappingRecord;
import flunav.types.ConveyorType;
import flunav.types.LocationType;
import flunav.types.PositionType;

@SpringBootTest(properties = {
        "springwolf.enabled=false",
        "rabbitmq.routing-key.item-events=1",
        "state-recovery.enabled=false",
        "graph-snapshot.enabled=false"
})
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class SensorMappingIntegrationTests extends BaseIntegrationTest {
    private final EventProcessor eventProcessor;
    private final SensorMappingService sensorMappingService;
    private final GraphService graphService;
    private final ItemService itemService;
    private final LocationService locationService;
    private final ConveyorService conveyorService;
    private final OrientDBService orientDBService;
    private final StringRedisTemplate redis;

    SensorMappingIntegrationTests(EventProcessor eventProcessor, SensorMappingService sensorMappingService,
            GraphService graphService, ItemService itemService, LocationService locationService,
            ConveyorService conveyorService, OrientDBService orientDBService, StringRedisTemplate redis) {
        this.eventProcessor = eventProcessor;
        this.sensorMappingService = sensorMappingService;
        this.graphService = graphService;
        this.itemService = itemService;
        this.locationService = locationService;
        this.conveyorService = conveyorService;
        this.orientDBService = orientDBService;
        this.redis = redis;
    }

    @BeforeEach
    void resetState() {
        DatabaseContextHolder.clearSimulation();
        Objects.requireNonNull(redis.getConnectionFactory()).getConnection().serverCommands().flushAll();
        orientDBService.resetMainDatabaseForTests("test-live");
    }

    @AfterEach
    void clearContext() {
        DatabaseContextHolder.clearSimulation();
    }

    @Test
    void sensorPositionResolvesToConfiguredConveyorProgressAndIsIncludedInGraphData() {
        createTopology();
        createItem("item-1", "source");
        Instant now = Instant.now().plusSeconds(60);

        eventProcessor.processEvent(new MapSensorMappingsEvent(
                List.of(new SensorMappingRecord("Scanner-A", "belt", 25.0))), false);
        eventProcessor.processEvent(new ItemPositionChangedEvent("item-1", "scanner-a", 0.0, now), false);

        ItemResponse item = graphService.getGraphData(now, false).getItems().stream()
                .filter(candidate -> "item-1".equals(candidate.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals("belt", item.getCurrentEdgeId());
        assertEquals(0.25, item.getProgress(), 0.0001);
        assertEquals(List.of("Scanner-A"), graphService.getGraphData(now, false).getSensorMappings().stream()
                .map(SensorMappingRecord::getSensorName).toList());
    }

    @Test
    void topologyIdTakesPrecedenceOverMatchingSensorAlias() {
        createTopology();
        createItem("item-2", "source");
        Instant now = Instant.now().plusSeconds(60);

        eventProcessor.processEvent(new MapSensorMappingsEvent(
                List.of(new SensorMappingRecord("belt", "other-belt", 80.0))), false);
        eventProcessor.processEvent(new ItemPositionChangedEvent("item-2", "belt", 10.0, now), false);

        ItemResponse item = graphService.getGraphData(now, false).getItems().stream()
                .filter(candidate -> "item-2".equals(candidate.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals("belt", item.getCurrentEdgeId());
        assertEquals(0.10, item.getProgress(), 0.0001);
    }

    @Test
    void rejectsDuplicateSensorNamesAndInvalidConveyors() {
        createTopology();

        assertThrows(IllegalArgumentException.class, () -> sensorMappingService.saveMappings(new MapSensorMappingsEvent(
                List.of(new SensorMappingRecord("Scanner", "belt", 20.0),
                        new SensorMappingRecord("scanner", "other-belt", 40.0)))));
        assertThrows(IllegalArgumentException.class, () -> sensorMappingService.saveMappings(new MapSensorMappingsEvent(
                List.of(new SensorMappingRecord("Scanner", "missing", 20.0)))));
    }

    private void createTopology() {
        createLocation("source");
        createLocation("target");
        createLocation("other-target");
        conveyorService.createConveyor("belt", "source", "target", "Belt", 100.0, 1.0, 0.0, true, true,
                ConveyorType.BELT, null, Map.of());
        conveyorService.createConveyor("other-belt", "source", "other-target", "Other belt", 100.0, 1.0, 0.0,
                true, true, ConveyorType.BELT, null, Map.of());
    }

    private void createLocation(String id) {
        locationService.createLocation(new LocationInput(id, id, 0.0, 0.0, null, null, LocationType.GENERIC, 10,
                true, false, Map.of()));
    }

    private void createItem(String id, String locationId) {
        ItemInput input = new ItemInput();
        input.setId(id);
        input.setName(id);
        input.setActive(true);
        input.setPriority(0.0);
        input.setLocationId(locationId);
        input.setPositionType(PositionType.LOCATION);
        input.setProperties(Map.of());
        input.setTimestamp(Instant.parse("2026-09-19T09:59:00Z"));
        itemService.createItem(input);
    }
}
