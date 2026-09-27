package com.flunav.backend.benchmark;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.flunav.backend.BackendApplication;
import com.flunav.backend.e2e.TestContainersEnvironment;
import com.flunav.backend.models.multisimulation.ArrivalConfiguration;
import com.flunav.backend.models.multisimulation.ArrivalDistribution;
import com.flunav.backend.models.multisimulation.ConveyorFailureConfiguration;
import com.flunav.backend.models.multisimulation.DestinationProbability;
import com.flunav.backend.models.multisimulation.MultiSimulationConfiguration;
import com.flunav.backend.models.multisimulation.MultiSimulationRun;
import com.flunav.backend.models.multisimulation.MultiSimulationRunMetrics;
import com.flunav.backend.models.multisimulation.MultiSimulationStatus;
import com.flunav.backend.services.EventProcessor;
import com.flunav.backend.services.MultiSimulationService;

import flunav.events.ConnectionCreatedEvent;
import flunav.events.DestinationExitMappingRecord;
import flunav.events.LocationCreatedEvent;
import flunav.events.MapDestinationExitsEvent;
import flunav.types.ConveyorType;
import flunav.types.LocationType;

public final class MultiSimulationBenchmarkRunner {

    private static final Duration MULTI_SIMULATION_TIMEOUT = Duration.ofMinutes(60);
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(java.time.ZoneOffset.UTC);

    private final EventProcessor eventProcessor;
    private final MultiSimulationService multiSimulationService;
    private final BenchmarkSuite suite;
    private final List<BenchmarkResult> results = new ArrayList<>();

    private MultiSimulationBenchmarkRunner(
            EventProcessor eventProcessor,
            MultiSimulationService multiSimulationService,
            BenchmarkSuite suite) {
        this.eventProcessor = eventProcessor;
        this.multiSimulationService = multiSimulationService;
        this.suite = suite;
    }

    public static void main(String[] args) throws Exception {
        BenchmarkSuite suite = BenchmarkSuite.fromSystemProperties();
        System.setProperty("disable-sim-cleanup", "true");
        TestContainersEnvironment.start();
        Map<String, Object> properties = springProperties(suite);
        properties.forEach((key, value) -> System.setProperty(key, String.valueOf(value)));
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(BackendApplication.class)
                .web(WebApplicationType.NONE)
                .properties(properties)
                .run(args)) {
            var runner = new MultiSimulationBenchmarkRunner(
                    context.getBean(EventProcessor.class),
                    context.getBean(MultiSimulationService.class),
                    suite);
            runner.run();
        } finally {
            TestContainersEnvironment.stop();
        }
    }

    private static Map<String, Object> springProperties(BenchmarkSuite suite) {
        Map<String, Object> properties = new LinkedHashMap<>(TestContainersEnvironment.springProperties(0));
        properties.put("springwolf.enabled", "false");
        properties.put("state-recovery.enabled", "false");
        properties.put("graph-snapshot.enabled", "false");
        properties.put("metric-snapshot.enabled", "false");
        properties.put("spring.rabbitmq.listener.simple.auto-startup", "false");
        properties.put("spring.rabbitmq.listener.direct.auto-startup", "false");
        properties.put("multi-simulation.max-runs", suite.maxRunCount());
        properties.put("multi-simulation.max-concurrent-runs", suite.maxConcurrentRuns());
        return properties;
    }

    private void run() throws Exception {
        Instant suiteStarted = Instant.now();
        for (BenchmarkConfig config : suite.configs()) {
            for (GraphSpec graph : config.graphs()) {
                for (int itemCount : config.itemCounts()) {
                    Topology topology = createTopology(graph, "benchmark-" + config.name() + "-" + graph.name()
                            + "-" + itemCount + "-" + System.nanoTime());
                    for (int runCount : config.runCounts()) {
                        runMultiSimulation(config, graph, topology, itemCount, runCount);
                    }
                }
            }
        }
        writeResults(suiteStarted, Instant.now());
    }

    private void runMultiSimulation(BenchmarkConfig config, GraphSpec graph, Topology topology, int itemCount,
            int runCount) {
        String name = "multi-simulation-" + config.name();
        MemorySnapshot beforeCreate = MemorySnapshot.capture("before-create");
        try {
            double durationHours = config.durationSeconds() / 3600.0;
            double arrivalRatePerHour = itemCount / durationHours;
            List<DestinationProbability> destinations = topology.exits().stream()
                    .map(exit -> new DestinationProbability(exit, 1.0 / topology.exits().size()))
                    .toList();
            List<ConveyorFailureConfiguration> failures = topology.conveyorIds().stream()
                    .limit(config.failuresPerScenario())
                    .map(conveyor -> new ConveyorFailureConfiguration(conveyor, 0.1, 60L))
                    .toList();
            var simulation = multiSimulationService.create(new MultiSimulationConfiguration(
                    "Benchmark " + graph.name() + " " + itemCount + " items x " + runCount + " runs",
                    config.durationSeconds(),
                    runCount,
                    new ArrivalConfiguration(arrivalRatePerHour, ArrivalDistribution.FIXED, 0.0),
                    topology.source(),
                    destinations,
                    failures,
                    1234L,
                    config.simulationStart()));

            MemorySnapshot before = MemorySnapshot.capture("before-start");
            long startedNanos = System.nanoTime();
            multiSimulationService.start(simulation.id());
            MemorySnapshot peakDuringExecution = waitForMultiSimulation(simulation.id(), MULTI_SIMULATION_TIMEOUT);
            long elapsedNanos = System.nanoTime() - startedNanos;
            var completed = multiSimulationService.get(simulation.id());
            List<MultiSimulationRun> runs = multiSimulationService.runs(simulation.id());
            RunTotals totals = RunTotals.from(runs);
            results.add(BenchmarkResult.success(
                    name,
                    graph,
                    itemCount,
                    runCount,
                    config.concurrentRuns(),
                    topology.locations(),
                    topology.conveyorCount(),
                    elapsedNanos,
                    before,
                    MemorySnapshot.capture("after"),
                    peakDuringExecution,
                    Map.ofEntries(
                            Map.entry("simulationId", simulation.id()),
                            Map.entry("preset", config.name()),
                            Map.entry("status", completed.status().name()),
                            Map.entry("durationSeconds", config.durationSeconds()),
                            Map.entry("arrivalRatePerHour", arrivalRatePerHour),
                            Map.entry("expectedItemsPerRun", itemCount),
                            Map.entry("completedRuns", completed.completedRuns()),
                            Map.entry("failedRuns", completed.failedRuns()),
                            Map.entry("itemsGenerated", totals.itemsGenerated()),
                            Map.entry("itemsCompleted", totals.itemsCompleted()),
                            Map.entry("itemsRemaining", totals.itemsRemaining()),
                            Map.entry("concurrentRuns", config.concurrentRuns()),
                            Map.entry("conveyorFailures", totals.conveyorFailures()),
                            Map.entry("recirculations", totals.recirculations()))));
        } catch (Exception failure) {
            results.add(BenchmarkResult.failure(
                    name,
                    graph,
                    itemCount,
                    runCount,
                    config.concurrentRuns(),
                    topology.locations(),
                    topology.conveyorCount(),
                    0L,
                    beforeCreate,
                    MemorySnapshot.capture("after"),
                    boundedMessage(failure)));
        }
    }

    private Topology createTopology(GraphSpec graph, String prefix) {
        String source = prefix + "-source";
        List<String> ingresses = ids(prefix, "ingress", graph.ingressCount());
        String merge = prefix + "-merge";
        String sorterA = prefix + "-sorter-a";
        String sorterB = prefix + "-sorter-b";
        String recirculation = prefix + "-recirculation";
        List<String> exits = ids(prefix, "exit", graph.exitCount());

        createLocation(source, "Benchmark Source", LocationType.GENERIC, 100_000, 0.0, 0.0);
        for (int index = 0; index < ingresses.size(); index++) {
            createLocation(ingresses.get(index), "Ingress " + (index + 1), LocationType.JUNCTION,
                    100_000, 1.0, index);
        }
        createLocation(merge, "Merge", LocationType.JUNCTION, 100_000, 2.0, 1.0);
        createLocation(sorterA, "Sorter A", LocationType.DECISION_POINT, 100_000, 3.0, 1.0);
        createLocation(sorterB, "Sorter B", LocationType.DECISION_POINT, 100_000, 4.0, 1.0);
        if (graph.recirculation()) {
            createLocation(recirculation, "Recirculation", LocationType.JUNCTION, 100_000, 3.0, 2.0);
        }
        for (int index = 0; index < exits.size(); index++) {
            createLocation(exits.get(index), "Exit " + (index + 1), LocationType.CHUTE,
                    100_000, 5.0, index);
        }

        List<String> conveyors = new ArrayList<>();
        for (String ingress : ingresses) {
            conveyors.add(createConveyor(prefix, conveyors.size(), source, ingress));
            conveyors.add(createConveyor(prefix, conveyors.size(), ingress, merge));
        }
        conveyors.add(createConveyor(prefix, conveyors.size(), merge, sorterA));
        for (int index = 0; index < exits.size(); index++) {
            if (index < Math.max(1, exits.size() / 2)) {
                conveyors.add(createConveyor(prefix, conveyors.size(), sorterA, exits.get(index)));
            } else {
                conveyors.add(createConveyor(prefix, conveyors.size(), sorterB, exits.get(index)));
            }
        }
        conveyors.add(createConveyor(prefix, conveyors.size(), sorterA, sorterB));
        if (graph.recirculation()) {
            conveyors.add(createConveyor(prefix, conveyors.size(), sorterB, recirculation));
            conveyors.add(createConveyor(prefix, conveyors.size(), recirculation, merge));
        }
        eventProcessor.process(new MapDestinationExitsEvent(exits.stream()
                .map(exit -> new DestinationExitMappingRecord(exit, List.of(exit)))
                .toList()), false).join();
        int locations = 1 + ingresses.size() + 3 + exits.size() + (graph.recirculation() ? 1 : 0);
        return new Topology(source, exits, conveyors, locations, conveyors.size());
    }

    private void createLocation(String id, String name, LocationType type, int capacity, double x, double y) {
        eventProcessor.process(new LocationCreatedEvent(
                id, name, true, x, y, type, capacity, new HashMap<>()), false).join();
    }

    private String createConveyor(String prefix, int index, String source, String target) {
        String id = prefix + "-conveyor-" + (index + 1);
        eventProcessor.process(new ConnectionCreatedEvent(
                id, source, target, 1.0, 10.0, 0.0, null, true,
                "Conveyor " + (index + 1), true, ConveyorType.BELT, 100_000, new HashMap<>()), false).join();
        return id;
    }

    private MemorySnapshot waitForMultiSimulation(String id, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        MemorySnapshot peak = MemorySnapshot.captureDuringExecution();
        while (System.nanoTime() < deadline) {
            MultiSimulationStatus status = multiSimulationService.get(id).status();
            peak = MemorySnapshot.peak(peak, MemorySnapshot.captureDuringExecution());
            if (status == MultiSimulationStatus.COMPLETED
                    || status == MultiSimulationStatus.COMPLETED_WITH_FAILURES
                    || status == MultiSimulationStatus.CANCELLED
                    || status == MultiSimulationStatus.FAILED) {
                return peak;
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("Multi-simulation did not finish before " + timeout);
    }

    private void writeResults(Instant suiteStarted, Instant suiteCompleted) throws IOException {
        Path output = suite.output();
        Files.createDirectories(output.getParent());
        BenchmarkReport report = new BenchmarkReport(
                suiteStarted,
                suiteCompleted,
                Duration.between(suiteStarted, suiteCompleted).toMillis(),
                suite,
                results);
        ObjectMapper mapper = new ObjectMapper()
                .findAndRegisterModules()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.writeValue(output.toFile(), report);
        writeCsv(output.resolveSibling(output.getFileName().toString().replaceFirst("\\.json$", ".csv")));
        mapper.writeValue(output.resolveSibling("latest-benchmark-results.json").toFile(), report);
        System.out.println("Benchmark results written to " + output.toAbsolutePath());
    }

    private void writeCsv(Path output) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("benchmark,graph,itemCount,runCount,concurrentRuns,locations,conveyors,durationSeconds,arrivalRatePerHour,success,elapsedMs,throughputItemsPerSecond,"
                + "heapUsedBeforeMiB,heapUsedAfterMiB,heapDeltaMiB,hostUsedBeforeMiB,hostUsedAfterMiB,"
                + "hostDeltaMiB,peakHeapUsedMiB,peakProcessRssMiB,failure");
        for (BenchmarkResult result : results) {
            lines.add(String.join(",",
                    csv(result.benchmark()),
                    csv(result.graphName()),
                    String.valueOf(result.itemCount()),
                    String.valueOf(result.runCount()),
                    String.valueOf(result.concurrentRuns()),
                    String.valueOf(result.locations()),
                    String.valueOf(result.conveyors()),
                    String.valueOf(result.metrics().getOrDefault("durationSeconds", "")),
                    String.valueOf(result.metrics().getOrDefault("arrivalRatePerHour", "")),
                    String.valueOf(result.success()),
                    String.valueOf(result.elapsedMillis()),
                    String.format(Locale.ROOT, "%.2f", result.throughputItemsPerSecond()),
                    String.valueOf(result.memoryBefore().heapUsedMiB()),
                    String.valueOf(result.memoryAfter().heapUsedMiB()),
                    String.valueOf(result.memoryAfter().heapUsedMiB() - result.memoryBefore().heapUsedMiB()),
                    String.valueOf(result.memoryBefore().hostUsedMiB()),
                    String.valueOf(result.memoryAfter().hostUsedMiB()),
                    String.valueOf(result.memoryAfter().hostUsedMiB() - result.memoryBefore().hostUsedMiB()),
                    result.peakDuringExecution() == null ? "" : String.valueOf(result.peakDuringExecution().heapUsedMiB()),
                    result.peakDuringExecution() == null ? "" : String.valueOf(result.peakDuringExecution().processRssMiB()),
                    csv(result.failure())));
        }
        Files.write(output, lines);
    }

    private static List<String> ids(String prefix, String kind, int count) {
        List<String> values = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            values.add(prefix + "-" + kind + "-" + index);
        }
        return values;
    }

    private static String boundedMessage(Throwable failure) {
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private record BenchmarkSuite(List<BenchmarkConfig> configs, Path output) {

        static BenchmarkSuite fromSystemProperties() {
            String presetNames = System.getProperty("flunav.benchmark.presets",
                    System.getProperty("flunav.benchmark.preset", "standard"));
            List<BenchmarkConfig> configs = new ArrayList<>();
            for (String raw : presetNames.split(",")) {
                String name = raw.trim();
                if (name.isEmpty()) {
                    continue;
                }
                if ("all".equals(name)) {
                    configs.addAll(BenchmarkPreset.fixedPresets().stream()
                            .map(BenchmarkConfig::fromPreset)
                            .toList());
                    continue;
                }
                configs.add(BenchmarkConfig.fromPreset(BenchmarkPreset.named(name)));
            }
            if (configs.isEmpty()) {
                throw new IllegalArgumentException("At least one benchmark preset is required");
            }
            String timestamp = FILE_TIMESTAMP.format(Instant.now());
            String defaultName = configs.size() == 1 ? configs.getFirst().name() : "multi-preset";
            Path defaultOutput = Path.of("target", "benchmark-results",
                    defaultName + "-benchmark-" + timestamp + ".json");
            return new BenchmarkSuite(
                    List.copyOf(configs),
                    Path.of(System.getProperty("flunav.benchmark.output", defaultOutput.toString())));
        }

        int maxRunCount() {
            return configs.stream().mapToInt(BenchmarkConfig::maxRunCount).max().orElse(1);
        }

        int maxConcurrentRuns() {
            return configs.stream().mapToInt(BenchmarkConfig::concurrentRuns).max().orElse(1);
        }
    }

    private record BenchmarkConfig(
            String name,
            List<GraphSpec> graphs,
            List<Integer> itemCounts,
            List<Integer> runCounts,
            long durationSeconds,
            int concurrentRuns,
            int failuresPerScenario,
            Instant simulationStart) {

        static BenchmarkConfig fromPreset(BenchmarkPreset preset) {
            return new BenchmarkConfig(
                    preset.name(),
                    parseGraphs(System.getProperty("flunav.benchmark.graphs", preset.graphs())),
                    parseIntegers(System.getProperty("flunav.benchmark.item-counts", preset.itemCounts())),
                    parseIntegers(System.getProperty("flunav.benchmark.run-counts", preset.runCounts())),
                    Long.getLong("flunav.benchmark.duration-seconds", preset.durationSeconds()),
                    Integer.getInteger("flunav.benchmark.concurrent-runs", preset.concurrentRuns()),
                    Integer.getInteger("flunav.benchmark.failures-per-scenario", preset.failuresPerScenario()),
                    Instant.parse(System.getProperty("flunav.benchmark.start", "2035-01-01T00:00:00Z")));
        }

        int maxRunCount() {
            return runCounts.stream().mapToInt(Integer::intValue).max().orElse(1);
        }

        private static List<GraphSpec> parseGraphs(String value) {
            List<GraphSpec> graphs = new ArrayList<>();
            for (String raw : value.split(",")) {
                String[] parts = raw.trim().split(":");
                if (parts.length != 4) {
                    throw new IllegalArgumentException("Graph spec must be name:ingressCount:exitCount:recirculation");
                }
                graphs.add(new GraphSpec(
                        parts[0],
                        Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]),
                        Boolean.parseBoolean(parts[3])));
            }
            return List.copyOf(graphs);
        }

        private static List<Integer> parseIntegers(String value) {
            List<Integer> numbers = new ArrayList<>();
            for (String raw : value.split(",")) {
                int number = Integer.parseInt(raw.trim());
                if (number <= 0) {
                    throw new IllegalArgumentException("Benchmark values must be positive: " + raw);
                }
                numbers.add(number);
            }
            return List.copyOf(numbers);
        }
    }

    private record BenchmarkPreset(
            String name,
            String graphs,
            String itemCounts,
            String runCounts,
            long durationSeconds,
            int concurrentRuns,
            int failuresPerScenario) {

        static BenchmarkPreset named(String name) {
            return switch (name) {
                case "smoke" -> new BenchmarkPreset(
                        "smoke", "tiny:1:1:false", "1", "1", 60L, 2, 0);
                case "small" -> new BenchmarkPreset(
                        "small", "small:1:2:false", "100,500", "1,10", 3600L, 4, 0);
                case "medium" -> new BenchmarkPreset(
                        "medium", "medium:3:5:true", "1000,2500", "10,50", 7200L, 8, 2);
                case "many-runs" -> new BenchmarkPreset(
                        "many-runs", "medium:3:5:true", "1000", "100,500", 7200L, 16, 2);
                case "long-hours" -> new BenchmarkPreset(
                        "long-hours", "medium:3:5:true", "4000,10000", "10,50", 28800L, 8, 2);
                case "big-graph" -> new BenchmarkPreset(
                        "big-graph", "large:8:12:true", "2500,5000", "10,50", 7200L, 16, 4);
                case "standard" -> new BenchmarkPreset(
                        "standard", "small:1:2:false,medium:3:5:true", "100,1000", "1,10", 3600L, 4, 0);
                default -> throw new IllegalArgumentException("Unknown benchmark preset: " + name);
            };
        }

        static List<BenchmarkPreset> fixedPresets() {
            return List.of(
                    named("smoke"),
                    named("small"),
                    named("medium"),
                    named("many-runs"),
                    named("long-hours"),
                    named("big-graph"));
        }
    }

    private record GraphSpec(String name, int ingressCount, int exitCount, boolean recirculation) {
    }

    private record Topology(
            String source,
            List<String> exits,
            List<String> conveyorIds,
            int locations,
            int conveyorCount) {
    }

    private record BenchmarkReport(
            Instant startedAt,
            Instant completedAt,
            long elapsedMillis,
            BenchmarkSuite suite,
            List<BenchmarkResult> results) {
    }

    private record BenchmarkResult(
            String benchmark,
            String graphName,
            int itemCount,
            int runCount,
            int concurrentRuns,
            int locations,
            int conveyors,
            boolean success,
            long elapsedMillis,
            double throughputItemsPerSecond,
            MemorySnapshot memoryBefore,
            MemorySnapshot memoryAfter,
            MemorySnapshot peakDuringExecution,
            Map<String, Object> metrics,
            String failure) {

        static BenchmarkResult success(
                String benchmark,
                GraphSpec graph,
                int itemCount,
                int runCount,
                int concurrentRuns,
                int locations,
                int conveyors,
                long elapsedNanos,
                MemorySnapshot before,
                MemorySnapshot after,
                MemorySnapshot peakDuringExecution,
                Map<String, Object> metrics) {
            return new BenchmarkResult(
                    benchmark,
                    graph.name(),
                    itemCount,
                    runCount,
                    concurrentRuns,
                    locations,
                    conveyors,
                    true,
                    TimeUnit.NANOSECONDS.toMillis(elapsedNanos),
                    throughput(itemCount, runCount, elapsedNanos),
                    before,
                    after,
                    peakDuringExecution,
                    metrics,
                    null);
        }

        static BenchmarkResult failure(
                String benchmark,
                GraphSpec graph,
                int itemCount,
                int runCount,
                int concurrentRuns,
                int locations,
                int conveyors,
                long elapsedNanos,
                MemorySnapshot before,
                MemorySnapshot after,
                String failure) {
            return new BenchmarkResult(
                    benchmark,
                    graph.name(),
                    itemCount,
                    runCount,
                    concurrentRuns,
                    locations,
                    conveyors,
                    false,
                    TimeUnit.NANOSECONDS.toMillis(elapsedNanos),
                    throughput(itemCount, runCount, elapsedNanos),
                    before,
                    after,
                    null,
                    Map.of(),
                    failure);
        }

        private static double throughput(int itemCount, int runCount, long elapsedNanos) {
            if (elapsedNanos <= 0) {
                return 0.0;
            }
            return (itemCount * (double) runCount) / (elapsedNanos / 1_000_000_000.0);
        }
    }

    private record MemorySnapshot(
            String phase,
            long heapUsedMiB,
            long heapCommittedMiB,
            long heapMaxMiB,
            long nonHeapUsedMiB,
            long hostTotalMiB,
            long hostUsedMiB,
            long hostAvailableMiB,
            long processRssMiB) {

        static MemorySnapshot capture(String phase) {
            System.gc();
            return captureWithoutGc(phase);
        }

        static MemorySnapshot captureDuringExecution() {
            return captureWithoutGc("running");
        }

        static MemorySnapshot peak(MemorySnapshot first, MemorySnapshot second) {
            return new MemorySnapshot(
                    "peak-during-execution",
                    Math.max(first.heapUsedMiB(), second.heapUsedMiB()),
                    Math.max(first.heapCommittedMiB(), second.heapCommittedMiB()),
                    Math.max(first.heapMaxMiB(), second.heapMaxMiB()),
                    Math.max(first.nonHeapUsedMiB(), second.nonHeapUsedMiB()),
                    Math.max(first.hostTotalMiB(), second.hostTotalMiB()),
                    Math.max(first.hostUsedMiB(), second.hostUsedMiB()),
                    Math.min(first.hostAvailableMiB(), second.hostAvailableMiB()),
                    Math.max(first.processRssMiB(), second.processRssMiB()));
        }

        private static MemorySnapshot captureWithoutGc(String phase) {
            MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
            long heapUsed = toMiB(memory.getHeapMemoryUsage().getUsed());
            long heapCommitted = toMiB(memory.getHeapMemoryUsage().getCommitted());
            long heapMax = toMiB(memory.getHeapMemoryUsage().getMax());
            long nonHeapUsed = toMiB(memory.getNonHeapMemoryUsage().getUsed());
            HostMemory host = HostMemory.capture();
            return new MemorySnapshot(
                    phase,
                    heapUsed,
                    heapCommitted,
                    heapMax,
                    nonHeapUsed,
                    host.totalMiB(),
                    host.usedMiB(),
                    host.availableMiB(),
                    host.processRssMiB());
        }

        private static long toMiB(long bytes) {
            if (bytes < 0) {
                return -1;
            }
            return bytes / 1024 / 1024;
        }
    }

    private record HostMemory(long totalMiB, long usedMiB, long availableMiB, long processRssMiB) {
        static HostMemory capture() {
            try {
                List<String> lines = Files.readAllLines(Path.of("/proc/meminfo"));
                long totalKiB = memoryValueKiB(lines, "MemTotal:");
                long availableKiB = memoryValueKiB(lines, "MemAvailable:");
                long processRssKiB = memoryValueKiB(Files.readAllLines(Path.of("/proc/self/status")), "VmRSS:");
                return new HostMemory(totalKiB / 1024, (totalKiB - availableKiB) / 1024, availableKiB / 1024,
                        processRssKiB / 1024);
            } catch (IOException | IllegalArgumentException ignored) {
                return new HostMemory(-1, -1, -1, -1);
            }
        }

        private static long memoryValueKiB(List<String> lines, String key) {
            return lines.stream()
                    .filter(line -> line.startsWith(key))
                    .findFirst()
                    .map(line -> line.substring(key.length()).trim().split("\\s+")[0])
                    .map(Long::parseLong)
                    .orElseThrow(() -> new IllegalArgumentException("Missing " + key));
        }
    }

    private record RunTotals(
            long itemsGenerated,
            long itemsCompleted,
            long itemsRemaining,
            long conveyorFailures,
            long recirculations) {

        static RunTotals from(List<MultiSimulationRun> runs) {
            long itemsGenerated = 0;
            long itemsCompleted = 0;
            long itemsRemaining = 0;
            long conveyorFailures = 0;
            long recirculations = 0;
            for (MultiSimulationRun run : runs) {
                MultiSimulationRunMetrics metrics = run.metrics();
                if (metrics == null) {
                    continue;
                }
                itemsGenerated += metrics.itemsGenerated();
                itemsCompleted += metrics.itemsCompleted();
                itemsRemaining += metrics.itemsRemaining();
                conveyorFailures += metrics.conveyorFailureCount();
                recirculations += metrics.recirculationCount();
            }
            return new RunTotals(itemsGenerated, itemsCompleted, itemsRemaining, conveyorFailures, recirculations);
        }
    }
}
