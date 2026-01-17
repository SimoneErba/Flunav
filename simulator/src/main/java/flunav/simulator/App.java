package flunav.simulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import flunav.events.*;
import flunav.types.LocationType;
import flunav.types.ConveyorType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public class App {

    private static final String BASE_URL = System.getenv().getOrDefault("BASE_URL", "http://localhost:8080/api");
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    public static final Logger logger = Logger.getLogger(App.class.getName());

    // CONFIGURATION
    private static final String MODE = System.getenv().getOrDefault("SIMULATION_MODE", "api"); // "api" or "rabbit"
    private static final String ACTION = System.getenv().getOrDefault("SIMULATION_ACTION", "setup"); // "setup" or
                                                                                                     // "destroy"

    private static final String RABBIT_HOST = System.getenv().getOrDefault("RABBIT_HOST", "localhost");
    private static final String RABBIT_QUEUE = "item-events-queue";
    private static final String RABBIT_EXCHANGE = "item-events-exchange";

    private static final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private static Connection rabbitConnection;
    private static Channel rabbitChannel;

    public static final AtomicLong itemCounter = new AtomicLong(0);
    private static final Random random = new Random();

    public static void main(String[] args) {
        if (args.length == 0) {
            logger.severe("Please specify a simulation to run. Usage: java App <line|loop|multi|large>");
            return;
        }

        try {
            if (MODE.equalsIgnoreCase("rabbit")) {
                setupRabbit();
            }

            Simulation simulation;
            String simulationType = args[0].toLowerCase();

            switch (simulationType) {
                case "line":
                    logger.info("--- Selected: LINE Simulation ---");
                    simulation = new LineSimulation();
                    break;
                case "loop":
                    logger.info("--- Selected: CONVEYOR LOOP Simulation ---");
                    simulation = new ConveyorLoopSimulation();
                    break;
                case "multi":
                    logger.info("--- Selected: MULTI-PATH SORTING Simulation ---");
                    simulation = new MultiPath();
                    break;
                case "large":
                    logger.info("--- Selected: LARGE SCALE REALISTIC Simulation ---");
                    simulation = new LargeLoopSimulation();
                    break;
                default:
                    logger.severe("Unknown simulation type: " + simulationType);
                    return;
            }

            if ("destroy".equalsIgnoreCase(ACTION)) {
                logger.info(">>> DESTROY MODE ACTIVATED <<<");
                simulation.destroy();
                logger.info(">>> Destruction Complete. Exiting. <<<");
            } else {
                logger.info(">>> SETUP & RUN MODE <<<");
                simulation.setup();
                simulation.run();
            }

        } catch (Exception e) {
            logger.log(Level.SEVERE, "A critical error occurred in the simulation", e);
        } finally {
            if ("destroy".equalsIgnoreCase(ACTION)) {
                System.exit(0);
            }
        }
    }

    // --- Simulation Interface ---

    interface Simulation {
        void setup() throws Exception;

        void run() throws Exception;

        void destroy() throws Exception;
    }

    // --- Large Scale Simulation ---
    static class LargeLoopSimulation implements Simulation {
        private static final int MAIN_LOOP_NODES = 100;
        private static final int NUM_ENTRANCES = 4;
        private static final int NUM_EXITS = 10;
        private static final double RADIUS = 200.0;

        // Track broken conveyors to repair them later
        private final List<String> brokenConveyors = new ArrayList<>();
        private final List<String> allConveyorIds = new ArrayList<>();
        private final List<String> allLocationIds = new ArrayList<>();

        @Override
        public void setup() throws Exception {
            logger.info("--- Setting up LARGE SCALE Facility ---");

            // 1. Create Main Loop Nodes (Circular)
            for (int i = 0; i < MAIN_LOOP_NODES; i++) {
                String id = "MainLoop-" + i;
                double angle = 2 * Math.PI * i / MAIN_LOOP_NODES;
                double lat = RADIUS * Math.sin(angle);
                double lon = RADIUS * Math.cos(angle);
                createLocation(id, lat, lon, LocationType.CONVEYOR);
                allLocationIds.add(id);
            }

            // 2. Create Entrances (Induction Lines) - Evenly spaced
            for (int i = 0; i < NUM_ENTRANCES; i++) {
                String id = "Spawn-" + i;
                // Position them outside the circle
                int targetIndex = (MAIN_LOOP_NODES / NUM_ENTRANCES) * i;
                double angle = 2 * Math.PI * targetIndex / MAIN_LOOP_NODES;
                double lat = (RADIUS + 40) * Math.sin(angle);
                double lon = (RADIUS + 40) * Math.cos(angle);

                createLocation(id, lat, lon, LocationType.JUNCTION);
                allLocationIds.add(id);
            }

            // 3. Create Exits (Chutes) - Evenly spaced
            for (int i = 0; i < NUM_EXITS; i++) {
                String id = "Chute-" + i;
                // Position them inside the circle
                int targetIndex = (MAIN_LOOP_NODES / NUM_EXITS) * i + 5; // Offset slightly
                double angle = 2 * Math.PI * targetIndex / MAIN_LOOP_NODES;
                double lat = (RADIUS - 40) * Math.sin(angle);
                double lon = (RADIUS - 40) * Math.cos(angle);

                createLocation(id, lat, lon, LocationType.CHUTE);
                allLocationIds.add(id);
            }

            logger.info("Waiting for nodes to persist...");
            Thread.sleep(2000);

            // 4. Connect Main Loop
            for (int i = 0; i < MAIN_LOOP_NODES; i++) {
                String from = "MainLoop-" + i;
                String to = "MainLoop-" + ((i + 1) % MAIN_LOOP_NODES);
                String edgeId = "Conv_" + from + "_" + to;
                createConveyor(from, to, 12.0, 2.0, true); // Main path
                allConveyorIds.add(edgeId);
            }

            // 5. Connect Entrances to Loop
            for (int i = 0; i < NUM_ENTRANCES; i++) {
                String from = "Spawn-" + i;
                int targetIndex = (MAIN_LOOP_NODES / NUM_ENTRANCES) * i;
                String to = "MainLoop-" + targetIndex;
                String edgeId = "Conv_" + from + "_" + to;
                createConveyor(from, to, 15.0, 1.5, false);
                allConveyorIds.add(edgeId);
            }

            // 6. Connect Loop to Exits
            for (int i = 0; i < NUM_EXITS; i++) {
                int sourceIndex = (MAIN_LOOP_NODES / NUM_EXITS) * i + 5;
                String from = "MainLoop-" + sourceIndex;
                String to = "Chute-" + i;
                String edgeId = "Conv_" + from + "_" + to;
                createConveyor(from, to, 10.0, 1.5, false);
                allConveyorIds.add(edgeId);
            }

            // 7. Create "Express Shortcuts" (Cross-paths) to allow multiple pathways
            // Shortcut 1: Across the circle
            createConveyor("MainLoop-10", "MainLoop-60", 250.0, 4.0, false);
            allConveyorIds.add("Conv_MainLoop-10_MainLoop-60");

            // Shortcut 2: Another chord
            createConveyor("MainLoop-40", "MainLoop-90", 250.0, 4.0, false);
            allConveyorIds.add("Conv_MainLoop-40_MainLoop-90");
        }

        @Override
        public void destroy() throws Exception {
            logger.info("--- Destroying Large Simulation ---");
            // This is a simplified destroy, in production you might want to track all IDs
            // created
            // For now, we rely on the naming convention
            for (String id : allConveyorIds) {
                // Extract source/target from ID convention Conv_Source_Target
                String[] parts = id.split("_");
                if (parts.length >= 3)
                    deleteConveyor(parts[1], parts[2]);
            }
            for (String id : allLocationIds)
                deleteLocation(id);

            logger.info("Cleaning up items...");
            for (int i = 0; i < 5000; i++) {
                try {
                    sendEvent(new ItemDeletedEvent("BoxLarge-" + i), "DELETE");
                } catch (Exception e) {
                }
            }
        }

        @Override
        public void run() throws Exception {
            logger.info("--- Starting Large Scale Simulation Loop ---");

            while (true) {
                // High throughput: New item every 500ms - 1.5s
                Thread.sleep(500 + random.nextInt(1000));

                // 1. INJECT NEW ITEM
                String itemId = "BoxLarge-" + itemCounter.incrementAndGet();
                String spawnPoint = "Spawn-" + random.nextInt(NUM_ENTRANCES);
                String destination = "Chute-" + random.nextInt(NUM_EXITS);

                // Generate realistic attributes
                Map<String, Object> attributes = new HashMap<>();
                attributes.put("weight", 0.5 + (random.nextDouble() * 20.0)); // 0.5kg to 20kg
                attributes.put("length", 20 + random.nextInt(60)); // 20cm to 80cm
                attributes.put("barcode", "L" + String.format("%09d", random.nextInt(1000000000)));
                attributes.put("priority", random.nextBoolean() ? "HIGH" : "NORMAL");

                // Clean up old instance if exists
                try {
                    sendEvent(new ItemDeletedEvent(itemId), "DELETE");
                } catch (Exception e) {
                }

                // Create
                sendEvent(new ItemCreatedEvent(itemId, itemId, 1.5, true, spawnPoint, 0.0, attributes), "POST");

                // Assign Destination
                // Small delay to simulate scanning at entry
                new Thread(() -> {
                    try {
                        Thread.sleep(200);
                        sendEvent(new ItemDestinationEvent(itemId, destination), "PUT");
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }).start();

                // 2. CHAOS MONKEY: Random Conveyor Breakdown (1% chance)
                if (random.nextInt(100) < 1 && !allConveyorIds.isEmpty()) {
                    String targetEdge = allConveyorIds.get(random.nextInt(allConveyorIds.size()));
                    if (!brokenConveyors.contains(targetEdge)) {
                        logger.warning("!!! BREAKDOWN SIMULATED on " + targetEdge + " !!!");

                        // Deactivate
                        sendEvent(new ConnectionDeactivatedEvent(targetEdge), "PUT");

                        // Add Error Property
                        Map<String, Object> props = new HashMap<>();
                        props.put("error_message", "BELT_FAILURE_ERR_0" + random.nextInt(9));
                        props.put("status", "ERROR");
                        sendEvent(new ConnectionPropertiesUpdatedEvent(targetEdge, props), "PUT");

                        brokenConveyors.add(targetEdge);
                    }
                }

                // 3. MAINTENANCE CREW: Repair broken conveyors (5% chance)
                if (!brokenConveyors.isEmpty() && random.nextInt(100) < 5) {
                    String fixedEdge = brokenConveyors.remove(0);
                    logger.info(">>> REPAIR COMPLETED on " + fixedEdge + " <<<");

                    // Activate
                    sendEvent(new ConnectionActivatedEvent(fixedEdge), "PUT");

                    // Clear Error Property
                    Map<String, Object> props = new HashMap<>();
                    props.put("error_message", null);
                    props.put("status", "OPERATIONAL");
                    sendEvent(new ConnectionPropertiesUpdatedEvent(fixedEdge, props), "PUT");
                }

                // 4. LOST ITEM / CHECKPOINT SCAN (1% chance)
                // Simulates an item that was "lost" (tracking drift) being re-discovered at a
                // random scanner
                if (random.nextInt(100) < 1) {
                    long lostIdNum = Math.max(1, itemCounter.get() - random.nextInt(50)); // Pick a recent item
                    String lostItemId = "BoxLarge-" + lostIdNum;
                    String randomCheckpoint = "MainLoop-" + random.nextInt(MAIN_LOOP_NODES);

                    logger.info("??? ITEM RE-ACQUIRED at checkpoint: " + lostItemId + " at " + randomCheckpoint);

                    // Force position update (Teleport/Correction)
                    // Note: In a real system, this would correct the drift.
                    sendEvent(new ItemPositionChangedEvent(lostItemId, randomCheckpoint, 0.0), "PUT");
                }
            }
        }
    }

    // --- Line Simulation ---
    static class LineSimulation implements Simulation {
        private static final int NUM_LOCATIONS = 10;
        private final List<String> locations = new ArrayList<>();

        @Override
        public void setup() throws Exception {
            logger.info("--- Setting up a line of " + NUM_LOCATIONS + " locations ---");
            for (int i = 0; i < NUM_LOCATIONS; i++) {
                String locationName = "LineLoc-" + i;
                createLocation(locationName, 0.0, i * 15.0,
                        i == NUM_LOCATIONS ? LocationType.CHUTE : LocationType.JUNCTION);
                locations.add(locationName);
            }

            logger.info("Waiting for nodes to persist...");
            Thread.sleep(1000);

            logger.info("--- Creating connections ---");
            for (int i = 0; i < NUM_LOCATIONS - 1; i++) {
                createConveyor(locations.get(i), locations.get(i + 1), 10.0, 2.0, true);
            }
        }

        @Override
        public void destroy() throws Exception {
            logger.info("--- Destroying Line Simulation ---");
            for (int i = 0; i < NUM_LOCATIONS - 1; i++) {
                deleteConveyor(locations.get(i), locations.get(i + 1));
            }
            for (int i = 0; i < NUM_LOCATIONS; i++) {
                deleteLocation("LineLoc-" + i);
            }
            logger.info("Cleaning up potential items...");
            for (int i = 0; i < 2000; i++) {
                try {
                    sendEvent(new ItemDeletedEvent("Item-" + i), "DELETE");
                } catch (Exception e) {
                }
            }
        }

        @Override
        public void run() throws Exception {
            logger.info("--- Starting periodic item injection at the start of the line ---");
            String entryPoint = locations.get(0);

            while (true) {
                long delay = 1000 + random.nextInt(4000);
                Thread.sleep(delay);

                String itemId = "Item-" + itemCounter.incrementAndGet();
                logger.info("Injecting new item '" + itemId + "' at entry point '" + entryPoint + "'");

                try {
                    sendEvent(new ItemDeletedEvent(itemId), "DELETE");
                } catch (Exception ignored) {
                }

                sendEvent(new ItemCreatedEvent(itemId, itemId, 1.0, true, entryPoint, 0.0, new HashMap<>()), "POST");
            }
        }
    }

    // --- Loop Simulation ---
    static class ConveyorLoopSimulation implements Simulation {
        private static final int NUM_MAIN_LOCATIONS = 8;
        private static final int NUM_ENTRANCES = 2;
        private static final int NUM_EXITS = 2;
        private static final double LAYOUT_RADIUS = 100.0;

        @Override
        public void setup() throws Exception {
            logger.info("--- Setting up a conveyor loop with entrances and exits ---");
            List<String> mainLoopLocations = new ArrayList<>();

            for (int i = 0; i < NUM_MAIN_LOCATIONS; i++) {
                String locName = "LoopLoc-" + i;
                double angle = 2 * Math.PI * i / NUM_MAIN_LOCATIONS;
                double lat = LAYOUT_RADIUS * Math.sin(angle);
                double lon = LAYOUT_RADIUS * Math.cos(angle);
                createLocation(locName, lat, lon, LocationType.JUNCTION);
                mainLoopLocations.add(locName);
            }

            for (int i = 0; i < NUM_ENTRANCES; i++) {
                createLocation("Entrance-" + i, 0.0, -150 - (i * 20.0), LocationType.JUNCTION);
            }

            for (int i = 0; i < NUM_EXITS; i++) {
                createLocation("Exit-" + i, 0.0, 150 + (i * 20.0), LocationType.CHUTE);
            }

            logger.info("Waiting for nodes to persist...");
            Thread.sleep(1000);

            logger.info("--- Creating connections for the loop ---");
            for (int i = 0; i < NUM_MAIN_LOCATIONS; i++) {
                createConveyor(mainLoopLocations.get(i), mainLoopLocations.get((i + 1) % NUM_MAIN_LOCATIONS), 20.0, 5.0,
                        true);
            }
            createConveyor("Entrance-0", "LoopLoc-0", 15.0, 2.0, false);
            createConveyor("Entrance-1", "LoopLoc-1", 15.0, 2.0, false);
            createConveyor("LoopLoc-4", "Exit-0", 10.0, 5.0, false);
            createConveyor("LoopLoc-5", "Exit-1", 10.0, 5.0, false);
        }

        @Override
        public void destroy() throws Exception {
            logger.info("--- Destroying Loop Simulation ---");
            for (int i = 0; i < NUM_MAIN_LOCATIONS; i++) {
                deleteConveyor("LoopLoc-" + i, "LoopLoc-" + ((i + 1) % NUM_MAIN_LOCATIONS));
            }
            deleteConveyor("Entrance-0", "LoopLoc-0");
            deleteConveyor("Entrance-1", "LoopLoc-1");
            deleteConveyor("LoopLoc-4", "Exit-0");
            deleteConveyor("LoopLoc-5", "Exit-1");

            for (int i = 0; i < NUM_MAIN_LOCATIONS; i++)
                deleteLocation("LoopLoc-" + i);
            for (int i = 0; i < NUM_ENTRANCES; i++)
                deleteLocation("Entrance-" + i);
            for (int i = 0; i < NUM_EXITS; i++)
                deleteLocation("Exit-" + i);

            logger.info("Cleaning up potential items...");
            for (int i = 0; i < 2000; i++) {
                try {
                    sendEvent(new ItemDeletedEvent("Item-" + i), "DELETE");
                } catch (Exception e) {
                }
            }
        }

        @Override
        public void run() throws Exception {
            logger.info("--- Starting periodic item injection at random entrances ---");
            while (true) {
                long delay = 1000 + random.nextInt(4000);
                Thread.sleep(delay);

                String itemId = "Item-" + itemCounter.incrementAndGet();
                String entryPoint = "Entrance-" + random.nextInt(NUM_ENTRANCES);

                logger.info("Injecting new item '" + itemId + "' at entry point '" + entryPoint + "'");

                try {
                    sendEvent(new ItemDeletedEvent(itemId), "DELETE");
                } catch (Exception ignored) {
                }

                sendEvent(new ItemCreatedEvent(itemId, itemId, 1.0, true, entryPoint, 0.0, new HashMap<>()), "POST");
            }
        }
    }

    // --- MultiPath Simulation ---
    static class MultiPath implements Simulation {

        @Override
        public void setup() throws Exception {
            logger.info("--- Setting up Sorting Hub Simulation ---");

            createLocation("Entry", 0, -20, LocationType.JUNCTION);
            createLocation("Hub", 0, 0, LocationType.JUNCTION);
            createLocation("Exit_A", 20, 20, LocationType.CHUTE);
            createLocation("Exit_B", 0, 20, LocationType.CHUTE);
            createLocation("Exit_Default", -20, 20, LocationType.CHUTE);

            logger.info("Waiting for nodes to persist...");
            Thread.sleep(1000);

            logger.info("--- Creating Connections ---");
            createConveyor("Entry", "Hub", 10.0, 2.0, false);
            createConveyor("Hub", "Exit_A", 20.0, 5.0, false);
            createConveyor("Hub", "Exit_B", 10.0, 0.5, false);
            createConveyor("Hub", "Exit_Default", 15.0, 1.0, true);
        }

        @Override
        public void destroy() throws Exception {
            logger.info("--- Destroying MultiPath Simulation ---");
            deleteConveyor("Entry", "Hub");
            deleteConveyor("Hub", "Exit_A");
            deleteConveyor("Hub", "Exit_B");
            deleteConveyor("Hub", "Exit_Default");
            deleteLocation("Entry");
            deleteLocation("Hub");
            deleteLocation("Exit_A");
            deleteLocation("Exit_B");
            deleteLocation("Exit_Default");

            logger.info("Cleaning up potential items...");
            for (int i = 0; i < 2000; i++) {
                try {
                    sendEvent(new ItemDeletedEvent("BoxMulti-" + i), "DELETE");
                } catch (Exception e) {
                }
            }
        }

        @Override
        public void run() throws Exception {
            logger.info("--- Starting Sorting Logic ---");
            int cycle = 0;
            while (true) {
                Thread.sleep(2000);
                String itemId = "BoxMulti-" + itemCounter.incrementAndGet();
                String destination = null;
                int scenario = cycle % 3;

                if (scenario == 0) {
                    destination = "Exit_A";
                    logger.info("Injecting " + itemId + " -> Target: " + destination + " (Should take FAST lane)");
                } else if (scenario == 1) {
                    destination = "Exit_B";
                    logger.info("Injecting " + itemId + " -> Target: " + destination + " (Should take SLOW lane)");
                } else {
                    destination = null;
                    logger.info("Injecting " + itemId + " -> No Target (Should take MAIN/DEFAULT lane)");
                }

                try {
                    sendEvent(new ItemDeletedEvent(itemId), "DELETE");
                } catch (Exception e) {
                }

                double weight = 1.0 + random.nextDouble() * 49.0;
                int height = 10 + random.nextInt(71);
                int width = 10 + random.nextInt(71);
                int depth = 10 + random.nextInt(71);
                long barcodeValue = random.nextLong(1_000_000_000_000L);
                String barcode = String.format("%012d", barcodeValue);

                Map<String, Object> attributes = new HashMap<>();
                attributes.put("weight", Double.parseDouble(String.format("%.2f", weight)));
                attributes.put("height", height);
                attributes.put("width", width);
                attributes.put("depth", depth);
                attributes.put("barcode", barcode);

                sendEvent(new ItemCreatedEvent(itemId, itemId, 1.0, true, "Entry", 0.0, attributes), "POST");

                if (destination != null) {
                    Thread.sleep(100);
                    sendEvent(new ItemDestinationEvent(itemId, destination), "PUT");
                }
                cycle++;
            }
        }
    }

    // --- Helper Methods ---

    private static void setupRabbit() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBIT_HOST);
        factory.setUsername("admin");
        factory.setPassword("admin");
        rabbitConnection = factory.newConnection();
        rabbitChannel = rabbitConnection.createChannel();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (rabbitChannel != null && rabbitChannel.isOpen())
                    rabbitChannel.close();
                if (rabbitConnection != null && rabbitConnection.isOpen())
                    rabbitConnection.close();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error closing RabbitMQ resources", e);
            }
        }));

        rabbitChannel.exchangeDeclare(RABBIT_EXCHANGE, "x-consistent-hash", true);
        rabbitChannel.queueDeclare(RABBIT_QUEUE, true, false, false, null);
        rabbitChannel.queueBind(RABBIT_QUEUE, RABBIT_EXCHANGE, "1");
        logger.info("RabbitMQ setup complete.");
    }

    public static void sendEvent(DomainEvent event, String httpMethod) throws Exception {
        String json = objectMapper.writeValueAsString(event);
        if (MODE.equalsIgnoreCase("rabbit")) {
            String hashKey = event instanceof EntityEvent ? ((EntityEvent) event).getEntityId() : "domainEvent";
            rabbitChannel.basicPublish(RABBIT_EXCHANGE, hashKey, null, json.getBytes());
            logger.info(() -> "Sent event to RabbitMQ with hashKey=" + hashKey + ": " + json);
        } else {
            String endpoint = getEndpointForEvent(event);
            if (endpoint == null) {
                logger.warning("No endpoint mapped for event type: " + event.getClass().getSimpleName());
                return;
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(new URI(BASE_URL + endpoint))
                    .header("Content-Type", "application/json")
                    .method(httpMethod, HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                logger.warning(() -> "Failed to send event to API: " + response.statusCode() + " " + response.body());
            }
        }
    }

    private static String getEndpointForEvent(DomainEvent event) {
        if (event instanceof ItemCreatedEvent)
            return "/items";
        if (event instanceof LocationCreatedEvent)
            return "/locations";
        if (event instanceof ItemPositionChangedEvent)
            return "/positions";
        if (event instanceof ConnectionCreatedEvent)
            return "/conveyors";
        if (event instanceof ItemDestinationEvent)
            return "/items";
        if (event instanceof ConnectionActivatedEvent)
            return "/conveyors";
        if (event instanceof ConnectionDeactivatedEvent)
            return "/conveyors";
        if (event instanceof ConnectionPropertiesUpdatedEvent)
            return "/conveyors";

        if (event instanceof ItemDeletedEvent)
            return "/items/" + ((EntityEvent) event).getEntityId();
        if (event instanceof LocationDeletedEvent)
            return "/locations/" + ((EntityEvent) event).getEntityId();
        if (event instanceof ConnectionDeletedEvent)
            return "/conveyors";

        return null;
    }

    private static void createLocation(String id, double lat, double lon, LocationType type) throws Exception {
        sendEvent(new LocationCreatedEvent(id, id, true, lat, lon, type, 0, new HashMap<>()), "POST");
    }

    private static void createConveyor(String from, String to, double length, double speed, boolean isMainPath)
            throws Exception {
        logger.info(
                () -> String.format("Creating conveyor from %s to %s [Len: %.1f, Spd: %.1f]", from, to, length, speed));
        long timeToTraverse = (long) ((length / speed) * 1000);
        ConnectionCreatedEvent event = new ConnectionCreatedEvent(
                "Conveyor_" + from + "_" + to, from, to, length, speed, timeToTraverse, isMainPath,
                "Conveyor_" + from + "_" + to, true, ConveyorType.BELT, 0, new HashMap<>());
        sendEvent(event, "POST");
    }

    private static void deleteLocation(String id) throws Exception {
        logger.info("Deleting Location: " + id);
        sendEvent(new LocationDeletedEvent(id), "DELETE");
    }

    private static void deleteConveyor(String from, String to) throws Exception {
        logger.info("Deleting Conveyor: " + from + " -> " + to);
        sendEvent(new ConnectionDeletedEvent(from, to), "DELETE");
    }
}