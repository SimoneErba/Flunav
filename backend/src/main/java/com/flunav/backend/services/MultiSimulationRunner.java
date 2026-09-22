package com.flunav.backend.services;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.multisimulation.MultiSimulation;
import com.flunav.backend.models.multisimulation.MultiSimulationReport;
import com.flunav.backend.models.multisimulation.MultiSimulationRun;
import com.flunav.backend.models.multisimulation.MultiSimulationRunMetrics;
import com.flunav.backend.models.multisimulation.MultiSimulationRunStatus;
import com.flunav.backend.models.multisimulation.MultiSimulationStatus;
import com.flunav.backend.models.simulation.SimulationState;

import flunav.events.ConnectionActivatedEvent;
import flunav.events.ConnectionDeactivatedEvent;
import flunav.events.DomainEvent;

@Service
public class MultiSimulationRunner {
    private static final Logger logger = LoggerFactory.getLogger(MultiSimulationRunner.class);

    private final ClickHouseService clickHouseService;
    private final SimulationService simulationService;
    private final MultiSimulationRandomGenerator randomGenerator;
    private final MultiSimulationMetricsService metricsService;
    private final MultiSimulationAggregator aggregator;
    private final TimeService timeService;
    private final long maximumEventsPerRun;
    private final Semaphore executionPermits;
    private final Map<String, AtomicBoolean> cancellations = new ConcurrentHashMap<>();

    public MultiSimulationRunner(
            ClickHouseService clickHouseService,
            SimulationService simulationService,
            MultiSimulationRandomGenerator randomGenerator,
            MultiSimulationMetricsService metricsService,
            MultiSimulationAggregator aggregator,
            TimeService timeService,
            @Value("${multi-simulation.max-events-per-run:5000000}") long maximumEventsPerRun,
            @Value("${multi-simulation.max-concurrent-experiments:1}") int maximumConcurrentExperiments) {
        this.clickHouseService = clickHouseService;
        this.simulationService = simulationService;
        this.randomGenerator = randomGenerator;
        this.metricsService = metricsService;
        this.aggregator = aggregator;
        this.timeService = timeService;
        this.maximumEventsPerRun = maximumEventsPerRun;
        this.executionPermits = new Semaphore(Math.max(1, maximumConcurrentExperiments));
    }

    public void requestCancellation(String id) {
        cancellations.computeIfAbsent(id, ignored -> new AtomicBoolean()).set(true);
    }

    /** Executes runs sequentially and advances only virtual timestamps. */
    @Async("taskExecutor")
    public void run(String id) {
        AtomicBoolean cancellation = cancellations.computeIfAbsent(id, ignored -> new AtomicBoolean());
        executionPermits.acquireUninterruptibly();
        try {
            runWithPermit(id, cancellation);
        } finally {
            cancellations.remove(id);
            executionPermits.release();
        }
    }

    private void runWithPermit(String id, AtomicBoolean cancellation) {
        MultiSimulation simulation = clickHouseService.getMultiSimulation(id).orElseThrow();
        Instant startedAt = timeService.physicalNow();
        saveSimulation(simulation, MultiSimulationStatus.RUNNING, 0, 0, startedAt, null, false, null);
        int completed = 0;
        int failed = 0;

        try {
            for (int runIndex = 0; runIndex < simulation.configuration().numberOfRuns(); runIndex++) {
                if (cancelled(cancellation)) {
                    break;
                }
                long seed = Math.addExact(simulation.baseSeed(), runIndex);
                MultiSimulationRun result = executeRun(simulation, runIndex, seed, cancellation);
                clickHouseService.saveMultiSimulationRun(result);
                if (result.status() == MultiSimulationRunStatus.COMPLETED) {
                    completed++;
                } else if (result.status() == MultiSimulationRunStatus.FAILED) {
                    failed++;
                }
                saveSimulation(simulation, MultiSimulationStatus.RUNNING, completed, failed,
                        startedAt, null, cancellation.get(), null);
            }

            List<MultiSimulationRun> runs = clickHouseService.getMultiSimulationRuns(id);
            Instant completedAt = timeService.physicalNow();
            MultiSimulationReport report = aggregator.aggregate(id, runs, completedAt);
            clickHouseService.saveMultiSimulationReport(report);
            boolean wasCancelled = cancelled(cancellation);
            MultiSimulationStatus status = wasCancelled
                    ? MultiSimulationStatus.CANCELLED
                    : failed > 0 ? MultiSimulationStatus.COMPLETED_WITH_FAILURES : MultiSimulationStatus.COMPLETED;
            saveSimulation(simulation, status, completed, failed, startedAt, completedAt, wasCancelled, null);
        } catch (Exception failure) {
            logger.error("Multi-simulation {} failed", id, failure);
            saveSimulation(simulation, MultiSimulationStatus.FAILED, completed, failed,
                    startedAt, timeService.physicalNow(), false, boundedMessage(failure));
        }
    }

    private MultiSimulationRun executeRun(
            MultiSimulation simulation,
            int runIndex,
            long seed,
            AtomicBoolean cancellation) {
        String runtimeId = simulation.id() + "_run_" + runIndex;
        Instant startedAt = timeService.physicalNow();
        var inputs = randomGenerator.generate(simulation.configuration(), seed, runtimeId);
        clickHouseService.saveMultiSimulationRun(new MultiSimulationRun(
                simulation.id(), runIndex, seed, MultiSimulationRunStatus.RUNNING,
                inputs.effectiveArrivalRate(), startedAt, null, null, null));
        boolean runtimeCreated = false;
        metricsService.start(runtimeId);
        try {
            SimulationState state = simulationService.createMultiSimulationRuntime(
                    runtimeId, simulation.configuration().simulationStartTime(), simulation.baseline());
            runtimeCreated = true;
            try (var context = DatabaseContextHolder.enterSimulationContext(runtimeId)) {
                for (DomainEvent event : inputs.events()) {
                    if (event instanceof ConnectionActivatedEvent || event instanceof ConnectionDeactivatedEvent) {
                        simulationService.addPlannedInternalEvent(event);
                    } else {
                        simulationService.addInternalEvent(event);
                    }
                }
            }

            Instant end = simulation.configuration().simulationStartTime()
                    .plusSeconds(simulation.configuration().simulationDurationSeconds());
            long processedEvents = 0;
            while (true) {
                if (cancelled(cancellation)) {
                    throw new RunCancelledException();
                }
                DomainEvent next = state.getInternalEventQueue().peek();
                if (next == null || next.getTimestamp().isAfter(end)) {
                    break;
                }
                if (++processedEvents > maximumEventsPerRun) {
                    throw new IllegalStateException("Run exceeded event safety limit " + maximumEventsPerRun);
                }
                simulationService.processNextInternalEvent(runtimeId);
            }
            simulationService.checkpointSimulationAt(runtimeId, end);
            MultiSimulationRunMetrics metrics = metricsService.finish(
                    runtimeId, simulation.configuration().simulationDurationSeconds(), end);
            return new MultiSimulationRun(
                    simulation.id(), runIndex, seed, MultiSimulationRunStatus.COMPLETED,
                    inputs.effectiveArrivalRate(), startedAt, timeService.physicalNow(), metrics, null);
        } catch (RunCancelledException cancelled) {
            return new MultiSimulationRun(
                    simulation.id(), runIndex, seed, MultiSimulationRunStatus.CANCELLED,
                    inputs.effectiveArrivalRate(), startedAt, timeService.physicalNow(), null, null);
        } catch (Exception failure) {
            logger.warn("Run {} of multi-simulation {} failed", runIndex, simulation.id(), failure);
            return new MultiSimulationRun(
                    simulation.id(), runIndex, seed, MultiSimulationRunStatus.FAILED,
                    inputs.effectiveArrivalRate(), startedAt, timeService.physicalNow(), null, boundedMessage(failure));
        } finally {
            metricsService.discard(runtimeId);
            if (runtimeCreated) {
                simulationService.destroySimulation(runtimeId);
            }
        }
    }

    private boolean cancelled(AtomicBoolean local) {
        return local.get();
    }

    private void saveSimulation(
            MultiSimulation source,
            MultiSimulationStatus status,
            int completed,
            int failed,
            Instant startedAt,
            Instant completedAt,
            boolean cancelRequested,
            String error) {
        clickHouseService.saveMultiSimulation(new MultiSimulation(
                source.id(), source.configuration(), source.baseline(), source.baseSeed(), status,
                completed, failed, source.createdAt(), startedAt, completedAt, cancelRequested, error));
    }

    private String boundedMessage(Throwable failure) {
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    private static final class RunCancelledException extends RuntimeException {
    }
}
