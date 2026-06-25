package com.flunav.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.domain.Role;
import com.flunav.backend.models.input.ItemInput;
import com.flunav.backend.models.input.LocationInput;
import com.flunav.backend.repositories.LiveItemRepository;
import com.flunav.backend.services.ClickHouseService;
import com.flunav.backend.services.ConveyorService;
import com.flunav.backend.services.GraphService;
import com.flunav.backend.services.ItemService;
import com.flunav.backend.services.LocationService;
import com.flunav.backend.services.OrientDBService;
import com.flunav.backend.utils.JwtUtils;
import flunav.events.ItemPathChangedEvent;
import flunav.types.LocationType;
import flunav.types.PositionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;

@SpringBootTest(properties = {
        "springwolf.enabled=false",
        "rabbitmq.routing-key.item-events=1",
        "state-recovery.enabled=false",
        "graph-snapshot.enabled=false"
})
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ItemPathUpdateIntegrationTests extends BaseIntegrationTest {

    private static final String RESET_SIMULATION_ID = "item-path-update-reset";

    private final MockMvc mockMvc;
    private final ItemService itemService;
    private final LocationService locationService;
    private final ConveyorService conveyorService;
    private final GraphService graphService;
    private final LiveItemRepository liveItemRepository;
    private final ClickHouseService clickHouseService;
    private final OrientDBService orientDBService;
    private final StringRedisTemplate redisTemplate;
    private final JwtUtils jwtUtils;
    private final ObjectMapper objectMapper;

    private String itemId;
    private String authorization;
    private String pathA;
    private String pathB;
    private String pathC;

    ItemPathUpdateIntegrationTests(
            MockMvc mockMvc,
            ItemService itemService,
            LocationService locationService,
            ConveyorService conveyorService,
            GraphService graphService,
            LiveItemRepository liveItemRepository,
            ClickHouseService clickHouseService,
            OrientDBService orientDBService,
            StringRedisTemplate redisTemplate,
            JwtUtils jwtUtils,
            ObjectMapper objectMapper) {
        this.mockMvc = mockMvc;
        this.itemService = itemService;
        this.locationService = locationService;
        this.conveyorService = conveyorService;
        this.graphService = graphService;
        this.liveItemRepository = liveItemRepository;
        this.clickHouseService = clickHouseService;
        this.orientDBService = orientDBService;
        this.redisTemplate = redisTemplate;
        this.jwtUtils = jwtUtils;
        this.objectMapper = objectMapper;
    }

    @BeforeEach
    void setup() {
        resetState();
        String suffix = UUID.randomUUID().toString();
        itemId = "path-item-" + suffix;
        authorization = "Bearer " + jwtUtils.generateToken("item-path-test", Role.ADMIN);
        pathA = "path-a-" + suffix;
        pathB = "path-b-" + suffix;
        pathC = "path-c-" + suffix;

        createLocation(pathA);
        createLocation(pathB);
        createLocation(pathC);
        conveyorService.createConveyor("path-a-b-" + suffix, pathA, pathB, "A to B", 10.0, 1.0, 0.0, true, true);
        conveyorService.createConveyor("path-b-c-" + suffix, pathB, pathC, "B to C", 10.0, 1.0, 0.0, true, true);

        ItemInput item = new ItemInput();
        item.setId(itemId);
        item.setName("Path Item");
        item.setActive(true);
        item.setPriority(0.0);
        item.setLocationId(pathA);
        item.setPositionType(PositionType.LOCATION);
        item.setDestinations(List.of("path-destination"));
        item.setSelectedExitId(pathC);
        item.setProperties(Map.of());
        item.setTimestamp(Instant.now());
        itemService.createItem(item);
    }

    @AfterEach
    void cleanup() {
        resetState();
    }

    @Test
    void dedicatedEndpointPersistsPathWithoutChangingDestinationAndRecordsEvent() throws Exception {
        Instant beforeUpdate = Instant.now().minusMillis(1);

        MockHttpServletResponse response = performPut(
                "/api/items/" + itemId + "/path",
                pathBody(pathA, pathB, pathC));

        assertEquals(200, response.getStatus());
        assertEquals(List.of(pathA, pathB, pathC), itemService.getItemById(itemId).getPath());
        assertEquals(pathC, itemService.getItemById(itemId).getSelectedExitId());
        assertEquals(List.of(pathA, pathB, pathC),
                graphService.getGraphData().getItems().getFirst().getPath());

        clickHouseService.flushEvents();
        ItemPathChangedEvent storedEvent = clickHouseService
                .getEventsBetween(beforeUpdate, Instant.now().plusSeconds(1))
                .stream()
                .filter(ItemPathChangedEvent.class::isInstance)
                .map(ItemPathChangedEvent.class::cast)
                .filter(event -> itemId.equals(event.getEntityId()))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of(pathA, pathB, pathC), storedEvent.getPath());
    }

    @Test
    void generalUpdateEndpointEmitsTheSamePathChange() throws Exception {
        MockHttpServletResponse response = performPut(
                "/api/items/" + itemId,
                pathBody(pathA, pathB));

        assertEquals(200, response.getStatus());
        assertEquals(List.of(pathA, pathB), liveItemRepository.getItemState(itemId).getPath());
        assertEquals(pathC, liveItemRepository.getItemState(itemId).getSelectedExitId());
    }

    @Test
    void emptyPathRemainsExplicitAcrossRedisReloadAndGraphRead() throws Exception {
        MockHttpServletResponse response = performPut(
                "/api/items/" + itemId + "/path",
                "{\"path\":[]}");

        assertEquals(200, response.getStatus());
        assertEquals("[]", redisTemplate.<String, String>opsForHash().get("item:" + itemId, "p"));
        assertEquals(List.of(), liveItemRepository.getItemState(itemId).getPath());
        assertEquals(List.of(), itemService.getItemById(itemId).getPath());
        assertEquals(List.of(), graphService.getGraphData().getItems().getFirst().getPath());
        assertEquals(pathC, itemService.getItemById(itemId).getSelectedExitId());
    }

    @Test
    void invalidPathPayloadsReturnBadRequest() throws Exception {
        assertEquals(400, performPut("/api/items/" + itemId, "{\"path\":\"" + pathA + "\"}").getStatus());
        assertEquals(400,
                performPut("/api/items/" + itemId + "/path", "{\"path\":[\"" + pathA + "\",3]}").getStatus());
        assertEquals(400, performPut("/api/items/" + itemId + "/path", "{\"path\":[\"\"]}").getStatus());
        assertEquals(400,
                performPut("/api/items/" + itemId + "/path", pathBody(pathA, "missing")).getStatus());
        assertEquals(400,
                performPut("/api/items/" + itemId + "/path", pathBody(pathA, pathC)).getStatus());
        assertEquals(400, performPut("/api/items/" + itemId + "/path", "{}").getStatus());
    }

    @Test
    void itemPathChangedEventRoundTripsWithItsStableType() throws Exception {
        ItemPathChangedEvent event = new ItemPathChangedEvent(itemId, List.of(pathA, pathB));

        var restored = objectMapper.readValue(objectMapper.writeValueAsBytes(event), flunav.events.DomainEvent.class);

        ItemPathChangedEvent restoredPathEvent = assertInstanceOf(ItemPathChangedEvent.class, restored);
        assertEquals("ITEM_PATH_CHANGED", restoredPathEvent.getEventType());
        assertEquals(List.of(pathA, pathB), restoredPathEvent.getPath());
    }

    @Test
    void itemPriorityUpdateRequiresTopLevelFiniteRangeAndRejectsPropertyCollision() throws Exception {
        assertEquals(400, performPut("/api/items/" + itemId, "{\"priority\":\"HIGH\"}").getStatus());
        assertEquals(400, performPut("/api/items/" + itemId, "{\"priority\":1.01}").getStatus());
        assertEquals(400, performPut("/api/items/" + itemId, "{\"properties\":{\"Priority\":0.5}}").getStatus());

        MockHttpServletResponse response = performPut("/api/items/" + itemId, "{\"priority\":0.65}");

        assertEquals(200, response.getStatus());
        assertEquals(0.65, itemService.getItemById(itemId).getPriority());
    }

    @Test
    void itemCreationRequiresValidTopLevelPriority() throws Exception {
        String base = "{\"id\":\"%s\",\"name\":\"Priority Item\",\"active\":true,"
                + "\"locationId\":\"%s\",\"positionType\":\"LOCATION\",\"properties\":{}}";
        assertEquals(400, performPost("/api/items", base.formatted("missing-priority", pathA)).getStatus());
        assertEquals(400, performPost("/api/items",
                base.formatted("string-priority", pathA).replace("\"properties\":{}",
                        "\"priority\":\"HIGH\",\"properties\":{}")).getStatus());
        assertEquals(400, performPost("/api/items",
                base.formatted("property-priority", pathA).replace("\"properties\":{}",
                        "\"priority\":0.5,\"properties\":{\"priority\":0.5}")).getStatus());

        MockHttpServletResponse response = performPost("/api/items",
                base.formatted("valid-priority", pathA).replace("\"properties\":{}",
                        "\"priority\":0.5,\"properties\":{}"));

        assertEquals(201, response.getStatus());
        assertEquals(0.5, itemService.getItemById("valid-priority").getPriority());
    }

    private MockHttpServletResponse performPut(String path, String body) throws Exception {
        MvcResult result = mockMvc.perform(put(path)
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result = mockMvc.perform(asyncDispatch(result)).andReturn();
        }
        return result.getResponse();
    }

    private MockHttpServletResponse performPost(String path, String body) throws Exception {
        MvcResult result = mockMvc.perform(post(path)
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result = mockMvc.perform(asyncDispatch(result)).andReturn();
        }
        return result.getResponse();
    }

    private void createLocation(String id) {
        locationService.createLocation(new LocationInput(
                id, id, 0.0, 0.0, null, null, LocationType.GENERIC, 100, true, false, Map.of()));
    }

    private String pathBody(String... path) throws Exception {
        return objectMapper.writeValueAsString(Map.of("path", path));
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
            orientDBService.resetMainDatabaseForTests(RESET_SIMULATION_ID);
        } catch (Exception ignored) {
        }
        DatabaseContextHolder.clearSimulation();
    }
}
