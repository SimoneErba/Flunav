package com.flunav.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flunav.backend.models.response.ThroughputMetric;
import com.flunav.backend.services.ClickHouseService;

import flunav.events.EntityEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestConstructor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "springwolf.enabled=false",
        "app.demo-mode=false",
        "stale-item-cleanup.enabled=false",
        "state-recovery.enabled=false",
        "graph-snapshot.enabled=false"
})
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AnalyticsThroughputTests extends BaseIntegrationTest {

    private final ClickHouseService clickHouseService;
    private final int serverPort;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    AnalyticsThroughputTests(ClickHouseService clickHouseService, @LocalServerPort int serverPort) {
        this.clickHouseService = clickHouseService;
        this.serverPort = serverPort;
    }

    @BeforeEach
    void setup() throws Exception {
        truncateAnalyticsTables();
    }

    @AfterEach
    void tearDown() throws Exception {
        truncateAnalyticsTables();
    }

    @Test
    void getThroughputHistoryAggregatesEnteredExitedAndMovementByMinute() throws Exception {
        Instant baseMinute = Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(10, ChronoUnit.MINUTES);
        Instant firstMinute = baseMinute;
        Instant skippedMinute = baseMinute.plus(1, ChronoUnit.MINUTES);
        Instant secondMinute = baseMinute.plus(2, ChronoUnit.MINUTES);
        Instant thirdMinute = baseMinute.plus(4, ChronoUnit.MINUTES);

        saveEvent("item-1", "ITEM_CREATED", firstMinute.plusSeconds(3));
        saveEvent("item-2", "ITEM_CREATED", firstMinute.plusSeconds(8));
        saveEvent("item-1", "ITEM_DELETED", firstMinute.plusSeconds(20));
        saveEvent("item-1", "ITEM_POSITION_CHANGED", firstMinute.plusSeconds(30));
        saveEvent("path-1", "PATH_TRAVERSED", firstMinute.plusSeconds(45));

        saveEvent("item-3", "ITEM_DELETED", secondMinute.plusSeconds(5));
        saveEvent("item-4", "ITEM_POSITION_CHANGED", secondMinute.plusSeconds(12));

        saveEvent("item-5", "ITEM_CREATED", thirdMinute.plusSeconds(1));
        saveEvent("path-2", "PATH_TRAVERSED", thirdMinute.plusSeconds(2));
        saveEvent("path-3", "PATH_TRAVERSED", thirdMinute.plusSeconds(3));
        flushEvents();

        Map<Instant, ThroughputMetric> metricsByMinute = clickHouseService.getThroughputHistory(24)
                .join()
                .stream()
                .collect(Collectors.toMap(ThroughputMetric::getTimestamp, Function.identity()));

        assertMetric(metricsByMinute, firstMinute, 2, 1, 2);
        assertMetric(metricsByMinute, secondMinute, 0, 1, 1);
        assertMetric(metricsByMinute, thirdMinute, 1, 0, 2);
        assertFalse(metricsByMinute.containsKey(skippedMinute), "Skipped minutes should not be zero-filled");
    }

    @Test
    void getThroughputHistoryHonorsHoursWindow() {
        Instant nowMinute = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        Instant oldMinute = nowMinute.minus(2, ChronoUnit.HOURS);
        Instant recentMinute = nowMinute.minus(30, ChronoUnit.MINUTES);

        saveEvent("old-item", "ITEM_CREATED", oldMinute.plusSeconds(1));
        saveEvent("recent-item", "ITEM_CREATED", recentMinute.plusSeconds(1));
        saveEvent("recent-item", "ITEM_DELETED", recentMinute.plusSeconds(2));
        flushEvents();

        List<ThroughputMetric> metrics = clickHouseService.getThroughputHistory(1).join();
        Map<Instant, ThroughputMetric> metricsByMinute = metrics.stream()
                .collect(Collectors.toMap(ThroughputMetric::getTimestamp, Function.identity()));

        assertFalse(metricsByMinute.containsKey(oldMinute), "Events outside the requested window must be excluded");
        assertMetric(metricsByMinute, recentMinute, 1, 1, 0);
        assertEquals(1, metrics.size());
    }

    @Test
    void getLatestThroughputAggregatesOnlyCurrentMinute() throws Exception {
        waitForStableCurrentMinute();
        Instant currentMinute = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        Instant previousMinute = currentMinute.minus(1, ChronoUnit.MINUTES);

        saveEvent("noise-entered", "ITEM_CREATED", previousMinute.plusSeconds(10));
        saveEvent("noise-exited", "ITEM_DELETED", previousMinute.plusSeconds(11));
        saveEvent("noise-moved", "PATH_TRAVERSED", previousMinute.plusSeconds(12));

        saveEvent("current-entered", "ITEM_CREATED", currentMinute.plusSeconds(2));
        saveEvent("current-exited", "ITEM_DELETED", currentMinute.plusSeconds(3));
        saveEvent("current-moved-1", "ITEM_POSITION_CHANGED", currentMinute.plusSeconds(4));
        saveEvent("current-moved-2", "PATH_TRAVERSED", currentMinute.plusSeconds(5));
        flushEvents();

        ThroughputMetric latest = clickHouseService.getLatestThroughput();

        assertEquals(currentMinute, latest.getTimestamp());
        assertEquals(1, latest.getItemsEntered());
        assertEquals(1, latest.getItemsExited());
        assertEquals(2, latest.getSegmentsProcessed());
    }

    @Test
    void getThroughputHistoryEndpointReturnsAggregatedValues() throws Exception {
        Instant minute = Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(5, ChronoUnit.MINUTES);
        saveEvent("endpoint-entered", "ITEM_CREATED", minute.plusSeconds(1));
        saveEvent("endpoint-exited", "ITEM_DELETED", minute.plusSeconds(2));
        saveEvent("endpoint-moved", "PATH_TRAVERSED", minute.plusSeconds(3));
        flushEvents();

        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/api/analytics/throughput/history?hours=24"))
                        .header("Authorization", "Bearer " + loginToken())
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        JsonNode metric = findMetric(objectMapper.readTree(response.body()), minute.toString());
        assertEquals(1, metric.get("itemsEntered").asLong());
        assertEquals(1, metric.get("itemsExited").asLong());
        assertEquals(1, metric.get("segmentsProcessed").asLong());
    }

    private void saveEvent(String entityId, String eventType, Instant timestamp) {
        clickHouseService.saveEventAsync(new TestEntityEvent(entityId, eventType, timestamp));
    }

    private void flushEvents() {
        clickHouseService.flushEvents();
    }

    private void truncateAnalyticsTables() throws Exception {
        clickHouseService.flushEvents();
        try (Connection connection = DriverManager.getConnection(
                CLICKHOUSE_CONTAINER.getJdbcUrl(),
                CLICKHOUSE_CONTAINER.getUsername(),
                CLICKHOUSE_CONTAINER.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE Events");
            statement.execute("TRUNCATE TABLE analytics_time_series");
        }
    }

    private void assertMetric(
            Map<Instant, ThroughputMetric> metricsByMinute,
            Instant minute,
            long itemsEntered,
            long itemsExited,
            long segmentsProcessed) {
        assertTrue(metricsByMinute.containsKey(minute), "Expected throughput metric for " + minute);
        ThroughputMetric metric = metricsByMinute.get(minute);
        assertEquals(itemsEntered, metric.getItemsEntered());
        assertEquals(itemsExited, metric.getItemsExited());
        assertEquals(segmentsProcessed, metric.getSegmentsProcessed());
    }

    private void waitForStableCurrentMinute() throws InterruptedException {
        long secondOfMinute = Instant.now().getEpochSecond() % 60;
        if (secondOfMinute >= 50) {
            Thread.sleep((61 - secondOfMinute) * 1000);
        }
    }

    private String loginToken() throws Exception {
        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/api/auth/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"username\":\"admin\",\"password\":\"Flun4v!\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return objectMapper.readTree(response.body()).get("token").asText();
    }

    private JsonNode findMetric(JsonNode metrics, String timestamp) {
        for (JsonNode metric : metrics) {
            if (timestamp.equals(metric.path("timestamp").asText())) {
                return metric;
            }
        }
        throw new IllegalStateException("Metric not found for " + timestamp);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + serverPort;
    }

    private static final class TestEntityEvent extends EntityEvent {
        private TestEntityEvent(String entityId, String eventType, Instant timestamp) {
            super(entityId, eventType, timestamp);
        }
    }
}
