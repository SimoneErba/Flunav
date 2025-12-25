package flonav.simulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import flonav.events.*;
import flonav.types.LocationType;
import flonav.types.ConveyorType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public class App {

    private static final String BASE_URL = "http://localhost:8080/api";
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    public static final Logger logger = Logger.getLogger(App.class.getName());

    // CONFIGURATION
    private static final String MODE = System.getenv().getOrDefault("SIMULATION_MODE", "api"); // "api" or "rabbit"
    private static final String ACTION = System.getenv().getOrDefault("SIMULATION_ACTION", "setup"); // "setup" or
                                                                                                     // "destroy"

    private static final String RABBIT_HOST = "localhost";
    private static final String RABBIT_QUEUE = "item-events-queue";
    private static final String RABBIT_EXCHANGE = "item-events-exchange";

    private static final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private static Connection rabbitConnection;
    private static Channel rabbitChannel;

    public static final AtomicLong itemCounter = new AtomicLong(0);
    private static final Random random = new Random();

    public static void main(String[] args) {
        if (args.length == 0) {
            logger.severe("Please specify a simulation to run. Usage: java App <line|loop|multi>");
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
            // Ensure we exit if in destroy mode (run() usually loops forever, but destroy()
            // finishes)
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

    // --- Line Simulation ---
    static class LineSimulation implements Simulation {
        private static final int NUM_LOCATIONS = 10;
        private final List<String> locations = new ArrayList<>();

        @Override
        public void setup() throws Exception {
            logger.info("--- Setting up a line of " + NUM_LOCATIONS + " locations ---");
            for (int i = 0; i < NUM_LOCATIONS; i++) {
                String locationName = "LineLoc-" + i;
                createLocation(locationName, 0.0, i * 15.0);
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
            // 1. Delete Connections
            for (int i = 0; i < NUM_LOCATIONS - 1; i++) {
                deleteConveyor(locations.get(i), locations.get(i + 1));
            }
            // 2. Delete Locations
            for (int i = 0; i < NUM_LOCATIONS; i++) {
                deleteLocation("LineLoc-" + i);
            }
            // 3. Try to clean up items (Best effort)
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

                // Cleanup previous if exists (for dev loop)
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

            // 1. Main Loop Nodes
            for (int i = 0; i < NUM_MAIN_LOCATIONS; i++) {
                String locName = "LoopLoc-" + i;
                double angle = 2 * Math.PI * i / NUM_MAIN_LOCATIONS;
                double lat = LAYOUT_RADIUS * Math.sin(angle);
                double lon = LAYOUT_RADIUS * Math.cos(angle);
                createLocation(locName, lat, lon);
                mainLoopLocations.add(locName);
            }

            // 2. Entrance Nodes
            for (int i = 0; i < NUM_ENTRANCES; i++) {
                createLocation("Entrance-" + i, 0.0, -150 - (i * 20.0));
            }

            // 3. Exit Nodes
            for (int i = 0; i < NUM_EXITS; i++) {
                createLocation("Exit-" + i, 0.0, 150 + (i * 20.0));
            }

            logger.info("Waiting for nodes to persist...");
            Thread.sleep(1000);

            // 3. Create connections
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
            // Delete Connections
            for (int i = 0; i < NUM_MAIN_LOCATIONS; i++) {
                deleteConveyor("LoopLoc-" + i, "LoopLoc-" + ((i + 1) % NUM_MAIN_LOCATIONS));
            }
            deleteConveyor("Entrance-0", "LoopLoc-0");
            deleteConveyor("Entrance-1", "LoopLoc-1");
            deleteConveyor("LoopLoc-4", "Exit-0");
            deleteConveyor("LoopLoc-5", "Exit-1");

            // Delete Locations
            for (int i = 0; i < NUM_MAIN_LOCATIONS; i++)
                deleteLocation("LoopLoc-" + i);
            for (int i = 0; i < NUM_ENTRANCES; i++)
                deleteLocation("Entrance-" + i);
            for (int i = 0; i < NUM_EXITS; i++)
                deleteLocation("Exit-" + i);

            // Cleanup Items
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
                // Random entrance
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

            // 1. Create Nodes
            createLocation("Entry", 0, -20);
            createLocation("Hub", 0, 0);
            createLocation("Exit_A", 20, 20); // Top Right
            createLocation("Exit_B", 0, 20); // Middle Right
            createLocation("Exit_Default", -20, 20); // Bottom Right

            logger.info("Waiting for nodes to persist...");
            Thread.sleep(1000);

            // 2. Create Connections
            logger.info("--- Creating Connections ---");

            // A. Feeder (Entry -> Hub)
            createConveyor("Entry", "Hub", 10.0, 2.0, false);

            // B. Path to Exit A (FAST but Long)
            createConveyor("Hub", "Exit_A", 20.0, 5.0, false);

            // C. Path to Exit B (SLOW but Short)
            createConveyor("Hub", "Exit_B", 10.0, 0.5, false);

            // D. Path to Default (Normal) - MAIN PATH
            createConveyor("Hub", "Exit_Default", 15.0, 1.0, true);
        }

        @Override
        public void destroy() throws Exception {
            logger.info("--- Destroying MultiPath Simulation ---");

            // Delete Connections
            deleteConveyor("Entry", "Hub");
            deleteConveyor("Hub", "Exit_A");
            deleteConveyor("Hub", "Exit_B");
            deleteConveyor("Hub", "Exit_Default");

            // Delete Locations
            deleteLocation("Entry");
            deleteLocation("Hub");
            deleteLocation("Exit_A");
            deleteLocation("Exit_B");
            deleteLocation("Exit_Default");

            // Cleanup Items
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
                Thread.sleep(2000); // Inject every 2 seconds

                String itemId = "BoxMulti-" + itemCounter.incrementAndGet();
                String destination = null;

                // Cycle through scenarios
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

                // 1. Create Item at Entry
                try {
                    sendEvent(new ItemDeletedEvent(itemId), "DELETE");
                } catch (Exception e) {
                }

                sendEvent(new ItemCreatedEvent(itemId, itemId, 1.0, true, "Entry", 0.0, new HashMap<>()), "POST");

                // 2. Set Destination (if applicable)
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

        if (event instanceof ItemDeletedEvent)
            return "/items/" + ((EntityEvent) event).getEntityId();
        if (event instanceof LocationDeletedEvent)
            return "/locations/" + ((EntityEvent) event).getEntityId();
        if (event instanceof ConnectionDeletedEvent)
            return "/conveyors"; // Uses query params in sendEvent

        return null;
    }

    // --- Creation Helpers ---
    private static void createLocation(String id, double lat, double lon) throws Exception {
        sendEvent(new LocationCreatedEvent(
                id, id, true, lat, lon, LocationType.JUNCTION, 0, new HashMap<>()), "POST");
    }

    private static void createConveyor(String from, String to, double length, double speed, boolean isMainPath)
            throws Exception {
        logger.info(
                () -> String.format("Creating conveyor from %s to %s [Len: %.1f, Spd: %.1f]", from, to, length, speed));

        long timeToTraverse = (long) ((length / speed) * 1000);

        ConnectionCreatedEvent event = new ConnectionCreatedEvent(
                "Conveyor_" + from + "_" + to,
                from,
                to,
                length,
                speed,
                timeToTraverse,
                isMainPath,
                "Conveyor_" + from + "_" + to,
                true,
                ConveyorType.BELT,
                0);

        sendEvent(event, "POST");
    }

    // --- Deletion Helpers ---
    private static void deleteLocation(String id) throws Exception {
        logger.info("Deleting Location: " + id);
        sendEvent(new LocationDeletedEvent(id), "DELETE");
    }

    private static void deleteConveyor(String from, String to) throws Exception {
        logger.info("Deleting Conveyor: " + from + " -> " + to);
        sendEvent(new ConnectionDeletedEvent(from, to), "DELETE");
    }
}