package fiumen.simulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import fiumen.events.DomainEvent;
import fiumen.events.EntityEvent;
import fiumen.events.ItemCreatedEvent;
import fiumen.events.ItemPositionChangedEvent;
import fiumen.events.LocationConnectionCreatedEvent;
import fiumen.events.LocationCreatedEvent;
import fiumen.types.LocationType;

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

    // --- Core Infrastructure (Shared across all simulations) ---
    private static final String BASE_URL = "http://localhost:8080/api";
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private static final Logger logger = Logger.getLogger(App.class.getName());
    private static final String MODE = System.getenv().getOrDefault("SIMULATION_MODE", "api"); // "api" or "rabbit"
    private static final String RABBIT_HOST = "localhost";
    private static final String RABBIT_QUEUE = "item-events-queue";
    private static final String RABBIT_EXCHANGE = "item-events-exchange";
    private static final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private static Connection rabbitConnection;
    private static Channel rabbitChannel;
    private static final Random random = new Random();
    private static final AtomicLong itemCounter = new AtomicLong(0);


    public static void main(String[] args) {
        if (args.length == 0) {
            logger.severe("Please specify a simulation to run. Usage: java App <line|loop>");
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
                    logger.info("--- Starting LINE Simulation ---");
                    simulation = new LineSimulation();
                    break;
                case "loop":
                    logger.info("--- Starting CONVEYOR LOOP Simulation ---");
                    simulation = new ConveyorLoopSimulation();
                    break;
                default:
                    logger.severe("Unknown simulation type: " + simulationType);
                    return;
            }

            simulation.setup();
            simulation.run();

        } catch (Exception e) {
            logger.log(Level.SEVERE, "A critical error occurred in the simulation", e);
        }
    }

    // --- Simulation Interface and Implementations ---

    /**
     * Defines the contract for a runnable simulation.
     */
    interface Simulation {
        void setup() throws Exception;
        void run() throws Exception;
    }

    /**
     * Simulates a straight line of conveyors, injecting new items at the start.
     */
    static class LineSimulation implements Simulation {
        private static final int NUM_LOCATIONS = 10;
        private final List<String> locations = new ArrayList<>();

        @Override
        public void setup() throws Exception {
            logger.info("--- Setting up a line of " + NUM_LOCATIONS + " locations ---");
            for (int i = 0; i < NUM_LOCATIONS; i++) {
                String locationName = "LineLoc-" + i;
                sendEvent(new LocationCreatedEvent(locationName, locationName, true, 0.0, i * 15.0, 10.0, 2.0, LocationType.CONVEYOR, 0, new HashMap<>()), "POST");
                locations.add(locationName);
            }

            logger.info("--- Creating connections to form a line ---");
            for (int i = 0; i < NUM_LOCATIONS - 1; i++) {
                createLocationConnection(locations.get(i), locations.get(i + 1));
            }
        }

        @Override
        public void run() throws Exception {
            logger.info("--- Starting periodic item injection at the start of the line ---");
            String entryPoint = locations.get(0);

            while (true) {
                // Wait for a random interval between 1 and 5 seconds
                long delay = 1000 + random.nextInt(4000);
                Thread.sleep(delay);

                String itemId = "Item-" + itemCounter.incrementAndGet();
                logger.info("Injecting new item '" + itemId + "' at entry point '" + entryPoint + "'");

                // 1. Create the item
                sendEvent(new ItemCreatedEvent(itemId, itemId, 1.0, true, new HashMap<>()), "POST");
                // 2. Place it at the start of the line
                sendEvent(new ItemPositionChangedEvent(itemId, entryPoint), "POST");
            }
        }
    }

    /**
     * Simulates a complex conveyor loop with dedicated entry and exit points.
     */
    static class ConveyorLoopSimulation implements Simulation {
        private static final int NUM_MAIN_LOCATIONS = 8;
        private static final int NUM_ENTRANCES = 2;
        private static final int NUM_EXITS = 2;
        private static final double LAYOUT_RADIUS = 100.0;

        private final List<String> entrances = new ArrayList<>();

        @Override
        public void setup() throws Exception {
            logger.info("--- Setting up a conveyor loop with entrances and exits ---");
            List<String> mainLoopLocations = new ArrayList<>();

            // 1. Create the main circular conveyor locations
            for (int i = 0; i < NUM_MAIN_LOCATIONS; i++) {
                String locName = "LoopLoc-" + i;
                double angle = 2 * Math.PI * i / NUM_MAIN_LOCATIONS;
                double lat = LAYOUT_RADIUS * Math.sin(angle);
                double lon = LAYOUT_RADIUS * Math.cos(angle);
                sendEvent(new LocationCreatedEvent(locName, locName, true, lat, lon, 20.0, 5.0, LocationType.CONVEYOR, 0, new HashMap<>()), "POST");
                mainLoopLocations.add(locName);
            }

            // 2. Create entrance and exit locations, placing them outside the main loop
            for (int i = 0; i < NUM_ENTRANCES; i++) {
                String entranceName = "Entrance-" + i;
                sendEvent(new LocationCreatedEvent(entranceName, entranceName, true, 0.0, -150 - (i * 20.0), 5.0, 2.0, LocationType.CONVEYOR, 0, new HashMap<>()), "POST");
                entrances.add(entranceName);
            }
            for (int i = 0; i < NUM_EXITS; i++) {
                String exitName = "Exit-" + i;
                // Exits are of type CHUTE
                sendEvent(new LocationCreatedEvent(exitName, exitName, true, 0.0, 150 + (i * 20.0), 5.0, 0.0, LocationType.CHUTE, 0, new HashMap<>()), "POST");
            }

            // 3. Create connections
            logger.info("--- Creating connections for the loop ---");
            // Connect main loop in a circle
            for (int i = 0; i < NUM_MAIN_LOCATIONS; i++) {
                createLocationConnection(mainLoopLocations.get(i), mainLoopLocations.get((i + 1) % NUM_MAIN_LOCATIONS));
            }
            // Connect entrances to the main loop
            createLocationConnection("Entrance-0", "LoopLoc-0");
            createLocationConnection("Entrance-1", "LoopLoc-1");
            // Connect main loop to exits
            createLocationConnection("LoopLoc-4", "Exit-0");
            createLocationConnection("LoopLoc-5", "Exit-1");
        }

        @Override
        public void run() throws Exception {
            logger.info("--- Starting periodic item injection at random entrances ---");
            while (true) {
                long delay = 1000 + random.nextInt(4000);
                Thread.sleep(delay);

                String itemId = "Item-" + itemCounter.incrementAndGet();
                String entryPoint = entrances.get(random.nextInt(entrances.size()));

                logger.info("Injecting new item '" + itemId + "' at entry point '" + entryPoint + "'");
                sendEvent(new ItemCreatedEvent(itemId, itemId, 1.0, true, new HashMap<>()), "POST");
                sendEvent(new ItemPositionChangedEvent(itemId, entryPoint), "POST");
            }
        }
    }


    // --- Communication and Helper Methods ---

    private static void setupRabbit() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBIT_HOST);
        factory.setUsername("admin");
        factory.setPassword("admin");
        rabbitConnection = factory.newConnection();
        rabbitChannel = rabbitConnection.createChannel();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (rabbitChannel != null && rabbitChannel.isOpen()) rabbitChannel.close();
                if (rabbitConnection != null && rabbitConnection.isOpen()) rabbitConnection.close();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error closing RabbitMQ resources", e);
            }
        }));

        rabbitChannel.exchangeDeclare(RABBIT_EXCHANGE, "x-consistent-hash", true);
        rabbitChannel.queueDeclare(RABBIT_QUEUE, true, false, false, null);
        rabbitChannel.queueBind(RABBIT_QUEUE, RABBIT_EXCHANGE, "1");
        logger.info("RabbitMQ setup complete.");
    }

    private static void sendEvent(EntityEvent event, String httpMethod) throws Exception {
        String json = objectMapper.writeValueAsString(event);
        if (MODE.equalsIgnoreCase("rabbit")) {
            String hashKey = event.getEntityId();
            rabbitChannel.basicPublish(RABBIT_EXCHANGE, hashKey, null, json.getBytes());
            logger.info(() -> "Sent event to RabbitMQ with hashKey=" + hashKey + ": " + json);
        } else {
            String endpoint = getEndpointForEvent(event);
            if (endpoint == null) return;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(new URI(BASE_URL + endpoint))
                    .header("Content-Type", "application/json")
                    .method(httpMethod, HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                logger.warning(() -> "Failed to send event to API: " + response.statusCode() + " " + response.body());
            }
        }
    }

    private static String getEndpointForEvent(DomainEvent event) {
        if (event instanceof ItemCreatedEvent) return "/items";
        if (event instanceof LocationCreatedEvent) return "/locations";
        if (event instanceof ItemPositionChangedEvent) return "/positions";
        if (event instanceof LocationConnectionCreatedEvent) return "/connections";
        return null;
    }

    private static void createLocationConnection(String from, String to) throws Exception {
        logger.info(() -> String.format("Creating connection from %s to %s", from, to));
        sendEvent(new LocationConnectionCreatedEvent(from, to), "POST");
    }
}