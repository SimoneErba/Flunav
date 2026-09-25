package com.flunav.backend.services;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.context.SimulationBuildCacheContext;
import com.flunav.backend.models.multisimulation.MultiSimulation;
import com.flunav.backend.models.multisimulation.MultiSimulationReport;
import com.flunav.backend.models.multisimulation.MultiSimulationRun;
import com.flunav.backend.models.multisimulation.MultiSimulationRunMetrics;
import com.flunav.backend.models.multisimulation.MultiSimulationRunStatus;
import com.flunav.backend.models.multisimulation.MultiSimulationStatus;
import com.flunav.backend.models.simulation.SimulationState;
import com.flunav.backend.utils.SimulationRunTiming;

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
    private final WebSocketService webSocketService;
    private final long maximumEventsPerRun;
    private final Semaphore executionPermits;
    private final int maximumConcurrentRuns;
    private final Map<String, AtomicBoolean> cancellations = new ConcurrentHashMap<>();

    public MultiSimulationRunner(
            ClickHouseService clickHouseService,
            SimulationService simulationService,
            MultiSimulationRandomGenerator randomGenerator,
            MultiSimulationMetricsService metricsService,
            MultiSimulationAggregator aggregator,
            TimeService timeService,
            WebSocketService webSocketService,
            @Value("${multi-simulation.max-events-per-run:5000000}") long maximumEventsPerRun,
            @Value("${multi-simulation.max-concurrent-experiments:1}") int maximumConcurrentExperiments,
            @Value("${multi-simulation.max-concurrent-runs:4}") int maximumConcurrentRuns) {
        this.clickHouseService = clickHouseService;
        this.simulationService = simulationService;
        this.randomGenerator = randomGenerator;
        this.metricsService = metricsService;
        this.aggregator = aggregator;
        this.timeService = timeService;
        this.webSocketService = webSocketService;
        this.maximumEventsPerRun = maximumEventsPerRun;
        this.executionPermits = new Semaphore(Math.max(1, maximumConcurrentExperiments));
        this.maximumConcurrentRuns = Math.max(1, maximumConcurrentRuns);
    }

    public void requestCancellation(String id) {
        cancellations.computeIfAbsent(id, ignored -> new AtomicBoolean()).set(true);
    }

    public int maximumConcurrentRuns() {
        return maximumConcurrentRuns;
    }

    /** Executes isolated runs concurrently while each run advances only virtual timestamps. */
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

        try (var workers = Executors.newFixedThreadPool(maximumConcurrentRuns)) {
            var completion = new ExecutorCompletionService<MultiSimulationRun>(workers);
            int nextRunIndex = 0;
            int inFlight = 0;
            int totalRuns = simulation.configuration().numberOfRuns();
            long progressStartedNanos = System.nanoTime();
            logger.info("Multi-simulation {} started totalRuns={} maxConcurrentRuns={}",
                    id, totalRuns, maximumConcurrentRuns);
            while (nextRunIndex < totalRuns || inFlight > 0) {
                while (!cancelled(cancellation) && nextRunIndex < totalRuns
                        && inFlight < maximumConcurrentRuns) {
                    int runIndex = nextRunIndex++;
                    long seed = Math.addExact(simulation.baseSeed(), runIndex);
                    completion.submit(() -> executeRun(simulation, runIndex, seed, cancellation));
                    inFlight++;
                }
                if (inFlight == 0) {
                    break;
                }
                Future<MultiSimulationRun> finished = completion.take();
                MultiSimulationRun result = finished.get();
                inFlight--;
                clickHouseService.saveMultiSimulationRun(result);
                if (result.status() == MultiSimulationRunStatus.COMPLETED) {
                    completed++;
                } else if (result.status() == MultiSimulationRunStatus.FAILED) {
                    failed++;
                }
                saveSimulation(simulation, MultiSimulationStatus.RUNNING, completed, failed,
                        startedAt, null, cancellation.get(), null);
                if (completed + failed == totalRuns
                        || (completed + failed) % 25 == 0) {
                    long elapsedNanos = System.nanoTime() - progressStartedNanos;
                    double runsPerSecond = (completed + failed) / (elapsedNanos / 1_000_000_000.0);
                    logger.info("Multi-simulation {} progress finishedRuns={}/{} completedRuns={} failedRuns={} "
                                    + "elapsedMs={} runsPerSecond={}",
                            id, completed + failed, totalRuns, completed, failed,
                            elapsedNanos / 1_000_000, String.format(java.util.Locale.ROOT, "%.2f", runsPerSecond));
                }
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
            logger.info("Multi-simulation {} finished status={} completedRuns={} failedRuns={} elapsedMs={}",
                    id, status, completed, failed,
                    (timeService.physicalNow().toEpochMilli() - startedAt.toEpochMilli()));
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
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
        try (var timing = SimulationRunTiming.start()) {
            try {
                return executeTimedRun(simulation, runIndex, seed, cancellation, runtimeId);
            } finally {
                logger.info("Multi-run timing {} wallMs={} sections=[{}]", runtimeId,
                        timing.elapsedMillis(), timing.summary());
            }
        }
    }

    private MultiSimulationRun executeTimedRun(
            MultiSimulation simulation,
            int runIndex,
            long seed,
            AtomicBoolean cancellation,
            String runtimeId) {
        Instant startedAt = timeService.physicalNow();
        long stepStarted = SimulationRunTiming.tick();
        var inputs = randomGenerator.generate(simulation.configuration(), seed, runtimeId);
        SimulationRunTiming.record("run.generate-inputs", stepStarted);
        stepStarted = SimulationRunTiming.tick();
        clickHouseService.saveMultiSimulationRun(new MultiSimulationRun(
                simulation.id(), runIndex, seed, MultiSimulationRunStatus.RUNNING,
                inputs.effectiveArrivalRate(), startedAt, null, null, null));
        SimulationRunTiming.record("run.save-start", stepStarted);
        boolean runtimeCreated = false;
        long initialActiveItems = simulation.baseline().graph().getItems() == null
                ? 0
                : simulation.baseline().graph().getItems().size();
        metricsService.start(runtimeId, initialActiveItems);
        try {
            stepStarted = SimulationRunTiming.tick();
            SimulationState state = simulationService.createMultiSimulationRuntime(
                    runtimeId, simulation.configuration().simulationStartTime(), simulation.baseline());
            SimulationRunTiming.record("run.restore-runtime", stepStarted);
            runtimeCreated = true;
            try (var context = DatabaseContextHolder.enterSimulationContext(runtimeId);
                    var cacheContext = SimulationBuildCacheContext.enterMultiRun(runtimeId)) {
                stepStarted = SimulationRunTiming.tick();
                simulationService.addPlannedInputEvents(inputs.events());
                SimulationRunTiming.record("run.queue-inputs", stepStarted);

                Instant end = simulation.configuration().simulationStartTime()
                        .plusSeconds(simulation.configuration().simulationDurationSeconds());
                long processedEvents = 0;
                long loopStarted = SimulationRunTiming.tick();
                long lastProgressLog = System.nanoTime();
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
                    if ((processedEvents & 255) == 0 && System.nanoTime() - lastProgressLog >= 10_000_000_000L) {
                        logger.info("Multi-run progress {} processedEvents={} wallMs={}", runtimeId,
                                processedEvents, (System.nanoTime() - loopStarted) / 1_000_000);
                        lastProgressLog = System.nanoTime();
                    }
                }
                SimulationRunTiming.record("run.event-loop", loopStarted);
                stepStarted = SimulationRunTiming.tick();
                simulationService.checkpointSimulationAt(runtimeId, end);
                SimulationRunTiming.record("run.final-checkpoint", stepStarted);
                stepStarted = SimulationRunTiming.tick();
                MultiSimulationRunMetrics metrics = metricsService.finish(
                        runtimeId, simulation.configuration().simulationDurationSeconds(), end);
                SimulationRunTiming.record("run.finish-metrics", stepStarted);
                return new MultiSimulationRun(
                        simulation.id(), runIndex, seed, MultiSimulationRunStatus.COMPLETED,
                        inputs.effectiveArrivalRate(), startedAt, timeService.physicalNow(), metrics, null);
            }
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
            stepStarted = SimulationRunTiming.tick();
            metricsService.discard(runtimeId);
            if (runtimeCreated) {
                simulationService.destroySimulation(runtimeId);
            }
            SimulationRunTiming.record("run.cleanup", stepStarted);
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
        MultiSimulation update = new MultiSimulation(
                source.id(), source.configuration(), source.baseline(), source.baseSeed(), status,
                completed, failed, source.createdAt(), startedAt, completedAt, cancelRequested, error);
        clickHouseService.saveMultiSimulation(update);
        webSocketService.broadcastMultiSimulationUpdate(update, timeService.physicalNow());
    }

    private String boundedMessage(Throwable failure) {
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    private static final class RunCancelledException extends RuntimeException {
    }
}
