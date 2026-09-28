package com.flunav.backend.services;

import com.clickhouse.client.api.Client;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flunav.backend.models.multisimulation.MultiSimulation;
import com.flunav.backend.models.multisimulation.MultiSimulationBaseline;
import com.flunav.backend.models.multisimulation.MultiSimulationConfiguration;
import com.flunav.backend.models.multisimulation.MultiSimulationReport;
import com.flunav.backend.models.multisimulation.MultiSimulationRun;
import com.flunav.backend.models.multisimulation.MultiSimulationRunMetrics;
import com.flunav.backend.models.multisimulation.MultiSimulationRunStatus;
import com.flunav.backend.models.multisimulation.MultiSimulationStatus;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import com.flunav.backend.services.ClickHouseService.*;
/** Stores experiments, runs, and reports using the facade client. */
final class ClickHouseMultiSimulationStorage extends ClickHouseAccess {
    private static final Logger logger = LoggerFactory.getLogger(ClickHouseMultiSimulationStorage.class);
    private final AtomicLong multiSimulationVersion = new AtomicLong(System.currentTimeMillis() * 1_000L);

    ClickHouseMultiSimulationStorage(Client client, ObjectMapper mapper, String database) {
        super(client, mapper, database);
    }

    public synchronized void saveMultiSimulation(MultiSimulation simulation) {
        try {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("experiment_id", simulation.id());
            row.put("name", simulation.configuration().name());
            row.put("status", simulation.status().name());
            row.put("configuration_json", objectMapper.writeValueAsString(simulation.configuration()));
            row.put("baseline_json", objectMapper.writeValueAsString(simulation.baseline()));
            row.put("topology_version", simulation.baseline().topologyVersion());
            row.put("configuration_version", simulation.baseline().configurationVersion());
            row.put("base_seed", simulation.baseSeed());
            row.put("total_runs", simulation.configuration().numberOfRuns());
            row.put("completed_runs", simulation.completedRuns());
            row.put("failed_runs", simulation.failedRuns());
            row.put("created_at", CLICKHOUSE_FORMATTER.format(simulation.createdAt()));
            row.put("started_at", formatNullable(simulation.startedAt()));
            row.put("completed_at", formatNullable(simulation.completedAt()));
            row.put("cancel_requested", simulation.cancelRequested() ? 1 : 0);
            row.put("error", Objects.toString(simulation.error(), ""));
            row.put("version", multiSimulationVersion.incrementAndGet());
            insertJsonRow("multi_simulations", row);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist multi-simulation " + simulation.id(), e);
        }
    }

    public Optional<MultiSimulation> getMultiSimulation(String id) {
        String sql = """
                SELECT * FROM %s.multi_simulations FINAL
                WHERE experiment_id = {id:String}
                FORMAT JSONEachRow
                """.formatted(clickhouseDatabase);
        return queryRows(sql, Map.of("id", id)).stream().findFirst().map(this::toMultiSimulation);
    }

    public List<MultiSimulation> getMultiSimulations() {
        String sql = """
                SELECT * FROM %s.multi_simulations FINAL
                ORDER BY created_at DESC
                FORMAT JSONEachRow
                """.formatted(clickhouseDatabase);
        return queryRows(sql, Map.of()).stream().map(this::toMultiSimulation).toList();
    }

    public synchronized void saveMultiSimulationRun(MultiSimulationRun run) {
        try {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("experiment_id", run.multiSimulationId());
            row.put("run_index", run.runIndex());
            row.put("seed", run.seed());
            row.put("status", run.status().name());
            row.put("effective_arrival_rate", run.effectiveArrivalRate());
            row.put("started_at", formatNullable(run.startedAt()));
            row.put("completed_at", formatNullable(run.completedAt()));
            row.put("metrics_json", run.metrics() == null ? "" : objectMapper.writeValueAsString(run.metrics()));
            row.put("error", Objects.toString(run.error(), ""));
            row.put("version", multiSimulationVersion.incrementAndGet());
            insertJsonRow("multi_simulation_runs", row);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist multi-simulation run", e);
        }
    }

    public List<MultiSimulationRun> getMultiSimulationRuns(String id) {
        String sql = """
                SELECT * FROM %s.multi_simulation_runs FINAL
                WHERE experiment_id = {id:String}
                ORDER BY run_index
                FORMAT JSONEachRow
                """.formatted(clickhouseDatabase);
        return queryRows(sql, Map.of("id", id)).stream().map(this::toMultiSimulationRun).toList();
    }

    public synchronized void saveMultiSimulationReport(MultiSimulationReport report) {
        try {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("experiment_id", report.multiSimulationId());
            row.put("report_json", objectMapper.writeValueAsString(report));
            row.put("generated_at", CLICKHOUSE_FORMATTER.format(report.generatedAt()));
            row.put("version", multiSimulationVersion.incrementAndGet());
            insertJsonRow("multi_simulation_reports", row);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist multi-simulation report", e);
        }
    }

    public Optional<MultiSimulationReport> getMultiSimulationReport(String id) {
        String sql = """
                SELECT report_json FROM %s.multi_simulation_reports FINAL
                WHERE experiment_id = {id:String}
                FORMAT JSONEachRow
                """.formatted(clickhouseDatabase);
        return queryRows(sql, Map.of("id", id)).stream().findFirst().map(row -> {
            try {
                return objectMapper.readValue(String.valueOf(row.get("report_json")), MultiSimulationReport.class);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to deserialize multi-simulation report", e);
            }
        });
    }

    private MultiSimulation toMultiSimulation(Map<String, Object> row) {
        try {
            MultiSimulationConfiguration configuration = objectMapper.readValue(
                    String.valueOf(row.get("configuration_json")), MultiSimulationConfiguration.class);
            MultiSimulationBaseline baseline = objectMapper.readValue(
                    String.valueOf(row.get("baseline_json")), MultiSimulationBaseline.class);
            return new MultiSimulation(
                    String.valueOf(row.get("experiment_id")),
                    configuration,
                    baseline,
                    Long.parseLong(String.valueOf(row.get("base_seed"))),
                    MultiSimulationStatus.valueOf(String.valueOf(row.get("status"))),
                    Integer.parseInt(String.valueOf(row.get("completed_runs"))),
                    Integer.parseInt(String.valueOf(row.get("failed_runs"))),
                    parseClickHouseInstant(row.get("created_at")),
                    parseClickHouseInstant(row.get("started_at")),
                    parseClickHouseInstant(row.get("completed_at")),
                    "1".equals(String.valueOf(row.get("cancel_requested")))
                            || Boolean.TRUE.equals(row.get("cancel_requested")),
                    blankToNull(row.get("error")));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize multi-simulation", e);
        }
    }

    private MultiSimulationRun toMultiSimulationRun(Map<String, Object> row) {
        try {
            String metricsJson = Objects.toString(row.get("metrics_json"), "");
            MultiSimulationRunMetrics metrics = metricsJson.isBlank()
                    ? null
                    : objectMapper.readValue(metricsJson, MultiSimulationRunMetrics.class);
            return new MultiSimulationRun(
                    String.valueOf(row.get("experiment_id")),
                    Integer.parseInt(String.valueOf(row.get("run_index"))),
                    Long.parseLong(String.valueOf(row.get("seed"))),
                    MultiSimulationRunStatus.valueOf(String.valueOf(row.get("status"))),
                    Double.parseDouble(String.valueOf(row.get("effective_arrival_rate"))),
                    parseClickHouseInstant(row.get("started_at")),
                    parseClickHouseInstant(row.get("completed_at")),
                    metrics,
                    blankToNull(row.get("error")));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize multi-simulation run", e);
        }
    }
}
