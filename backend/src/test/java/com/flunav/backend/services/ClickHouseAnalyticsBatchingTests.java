package com.flunav.backend.services;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.flunav.backend.config.ClickHouseSchedulingConfig;
import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.analytics.AnomalyDetectorType;
import com.flunav.backend.models.analytics.DetectorBaseline;
import com.flunav.backend.models.analytics.AnomalyFinding;
import com.flunav.backend.models.analytics.AnomalyIncident;
import com.flunav.backend.models.analytics.AnomalyProcessingMode;
import com.flunav.backend.models.analytics.AlarmPromotionState;
import com.flunav.backend.models.analytics.LocationFlowObservation;
import com.flunav.backend.test.ClickHouseTestContainerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.testcontainers.clickhouse.ClickHouseContainer;
import flunav.types.AlarmSeverity;
import flunav.types.ComponentType;

@SpringJUnitConfig(ClickHouseAnalyticsBatchingTests.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ClickHouseAnalyticsBatchingTests {
    private static final Instant TIMESTAMP = Instant.parse("2048-03-01T12:00:00.123Z");
    private static final List<String> TABLES = List.of("analytics_detector_baselines", "analytics_component_flow_1m",
            "analytics_component_flow_events", "analytics_anomaly_findings", "analytics_anomaly_incidents");
    private final String scope = "batching-" + UUID.randomUUID();

    @Autowired
    private ClickHouseService service;
    @Autowired
    private ClickHouseContainer container;
    @Autowired
    @Qualifier("taskScheduler")
    private ThreadPoolTaskScheduler defaultScheduler;

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @Import(ClickHouseSchedulingConfig.class)
    static class Config {
        @Bean
        ThreadPoolTaskSchedulerBuilder taskSchedulerBuilder() {
            return new ThreadPoolTaskSchedulerBuilder().poolSize(1);
        }

        @Bean(destroyMethod = "stop")
        ClickHouseContainer clickHouseContainer() {
            ClickHouseContainer container = ClickHouseTestContainerFactory.create();
            container.start();
            return container;
        }

        @Bean(destroyMethod = "cleanup")
        ClickHouseService clickHouseService(ClickHouseContainer container) {
            return new ClickHouseService(
                    "http://" + container.getHost() + ":" + container.getMappedPort(8123) + "/default",
                    container.getUsername(), container.getPassword(),
                    JsonMapper.builder().findAndAddModules().build());
        }
    }

    @AfterEach
    void cleanRows() throws Exception {
        synchronized (service) {
            service.flushAnalyticsBatches();
            for (String table : TABLES) {
                execute("ALTER TABLE " + table + " DELETE WHERE scope_id IN ('" + scope + "', '"
                        + scope + "-keep') SETTINGS mutations_sync=1");
            }
        }
    }

    @Test
    void scheduledFlushPreservesScopeAndVirtualTimestampAfterContextCloses() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        defaultScheduler.execute(() -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS));
            synchronized (service) {
                try (var context = DatabaseContextHolder.enterSimulationContext(scope)) {
                    service.saveDetectorBaseline(baseline(scope, "baseline"));
                    service.saveComponentFlowBucket(TIMESTAMP, scope, scope, "location", "LOCATION", 5, 3);
                }
                assertEquals(0, count("analytics_detector_baselines", scope));
                assertEquals(0, count("analytics_component_flow_1m", scope));
            }
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                assertEquals(1, count("analytics_detector_baselines", scope));
                assertEquals(1, count("analytics_component_flow_1m", scope));
            });
        } finally {
            release.countDown();
        }
        assertEquals(TIMESTAMP.toEpochMilli(), scalar("SELECT toUnixTimestamp64Milli(calculated_at) FROM "
                + "analytics_detector_baselines WHERE scope_id='" + scope + "'"));
        assertEquals(TIMESTAMP.toEpochMilli(), scalar("SELECT toUnixTimestamp64Milli(bucket_start) FROM "
                + "analytics_component_flow_1m WHERE simulation_id='" + scope + "'"));
        assertEquals(2, scalar("SELECT pressure FROM analytics_component_flow_1m WHERE scope_id='" + scope + "'"));
    }

    @Test
    void eachQueueFlushesAtOneHundredThousandRowsInOneInsert() throws Exception {
        synchronized (service) {
            for (int index = 0; index < 99_999; index++) {
                service.saveDetectorBaseline(baseline(scope, "baseline-" + index));
                service.saveComponentFlowBucket(TIMESTAMP, scope, scope, "location-" + index, "LOCATION", 5, 3);
            }
            assertEquals(0, count("analytics_detector_baselines", scope));
            assertEquals(0, count("analytics_component_flow_1m", scope));
            long insertsBefore = scalar("SELECT value FROM system.events WHERE event='InsertQuery'");
            service.saveDetectorBaseline(baseline(scope, "baseline-final"));
            service.saveComponentFlowBucket(TIMESTAMP, scope, scope, "location-final", "LOCATION", 5, 3);
            assertEquals(100_000, count("analytics_detector_baselines", scope));
            assertEquals(100_000, count("analytics_component_flow_1m", scope));
            assertEquals(2, scalar("SELECT value FROM system.events WHERE event='InsertQuery'") - insertsBefore);
        }
    }

    @Test
    void failedBatchesRemainQueuedUntilClickHouseCanAcceptThem() throws Exception {
        synchronized (service) {
            for (String table : new String[] {"analytics_detector_baselines", "analytics_component_flow_1m"}) {
                service.saveDetectorBaseline(baseline(scope, "baseline-" + table));
                service.saveComponentFlowBucket(TIMESTAMP, scope, scope, "location-" + table, "LOCATION", 5, 3);
                execute("RENAME TABLE " + table + " TO " + table + "_unavailable");
                try {
                    if (table.equals("analytics_detector_baselines")) {
                        service.flushDetectorBaselines();
                    } else {
                        service.flushComponentFlowBuckets();
                    }
                } finally {
                    execute("RENAME TABLE " + table + "_unavailable TO " + table);
                }
            }
            service.flushDetectorBaselines();
            service.flushComponentFlowBuckets();
            assertEquals(2, count("analytics_detector_baselines", scope));
            assertEquals(2, count("analytics_component_flow_1m", scope));
        }
    }

    @Test
    void simulationCleanupRemovesPendingRowsWithoutRemovingOtherScopes() throws Exception {
        synchronized (service) {
            for (String queuedScope : new String[] {scope, scope + "-keep"}) {
                service.saveDetectorBaseline(baseline(queuedScope, "baseline"));
                service.saveComponentFlowBucket(TIMESTAMP, queuedScope, queuedScope, "location", "LOCATION", 5, 3);
                enqueueAnomalyRows(queuedScope);
            }
            service.deleteOperationalAnalyticsForSimulation(scope);
            service.flushAnalyticsBatches();
            for (String table : TABLES) {
                assertEquals(0, count(table, scope));
                assertEquals(1, count(table, scope + "-keep"));
            }
        }
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void shutdownDrainsPendingRows() throws Exception {
        synchronized (service) {
            service.saveDetectorBaseline(baseline(scope, "baseline"));
            service.saveComponentFlowBucket(TIMESTAMP, scope, scope, "location", "LOCATION", 5, 3);
            enqueueAnomalyRows(scope);
            service.cleanup();
            for (String table : TABLES) {
                assertEquals(1, count(table, scope));
            }
        }
    }

    @Test
    void flowObservationsFindingsAndIncidentsShareTheScheduledBatchFlush() throws Exception {
        synchronized (service) {
            enqueueAnomalyRows(scope);
            for (String table : TABLES.subList(2, TABLES.size())) {
                assertEquals(0, count(table, scope));
            }
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            for (String table : TABLES.subList(2, TABLES.size())) {
                assertEquals(1, count(table, scope));
            }
        });
    }

    @Test
    void findingUpdatesKeepTheLatestStatusWithinOneBatchAndAfterRetry() throws Exception {
        synchronized (service) {
            service.saveAnomalyFinding(finding(scope, AlarmPromotionState.PENDING));
            service.saveAnomalyFinding(finding(scope, AlarmPromotionState.ACTIVE));
            execute("RENAME TABLE analytics_anomaly_findings TO analytics_anomaly_findings_unavailable");
            try {
                service.flushAnalyticsBatches();
                service.saveAnomalyFinding(finding(scope, AlarmPromotionState.ALREADY_ACTIVE));
            } finally {
                execute("RENAME TABLE analytics_anomaly_findings_unavailable TO analytics_anomaly_findings");
            }
            service.flushAnalyticsBatches();
            try (Connection connection = connection(); Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT alarm_state FROM analytics_anomaly_findings FINAL "
                            + "WHERE scope_id='" + scope + "'")) {
                assertTrue(rows.next());
                assertEquals("ALREADY_ACTIVE", rows.getString(1));
                assertEquals(false, rows.next());
            }
        }
    }

    private void enqueueAnomalyRows(String rowScope) {
        service.saveComponentFlowObservation(new LocationFlowObservation("flow-event", "item", "location",
                LocationFlowObservation.Direction.ARRIVAL, TIMESTAMP), rowScope, rowScope);
        service.saveAnomalyFinding(finding(rowScope, AlarmPromotionState.ACTIVE));
        service.saveAnomalyIncident(new AnomalyIncident("incident", rowScope, rowScope, "location", "HIGH",
                List.of("finding"), List.of("location"), TIMESTAMP, TIMESTAMP));
    }

    private AnomalyFinding finding(String rowScope, AlarmPromotionState state) {
        return new AnomalyFinding("finding", rowScope, rowScope, AnomalyDetectorType.OCCUPANCY_JAM, "v1",
                AnomalyProcessingMode.FUTURE_SIMULATION, "location", ComponentType.LOCATION, "item", null, null,
                List.of(), Map.of("occupancy", 2), null, null, null, null, null, null, 0, null, null,
                AlarmSeverity.WARNING, "alarm", state, TIMESTAMP, TIMESTAMP);
    }

    private DetectorBaseline baseline(String rowScope, String id) {
        return new DetectorBaseline(id, rowScope, "v1", AnomalyDetectorType.FLOW_TURBULENCE, "location", "path",
                TIMESTAMP.minusSeconds(300), TIMESTAMP, 20, 5.0, 1.0, 5.0, 0.5, 3.0, 7.0, TIMESTAMP, TIMESTAMP);
    }

    private long count(String table, String rowScope) throws Exception {
        return scalar("SELECT count() FROM " + table + " WHERE scope_id='" + rowScope + "'");
    }

    private long scalar(String sql) throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }
}
