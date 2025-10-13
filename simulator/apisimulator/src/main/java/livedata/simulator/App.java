package livedata.simulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import flumen.events.DomainEvent;
import flumen.events.EntityEvent;
import flumen.events.ItemCreatedEvent;
import flumen.events.ItemPositionChangedEvent;
import flumen.events.LocationCreatedEvent;
import flumen.types.LocationType;
import flumen.events.LocationConnectionCreatedEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class App {

    private static final String BASE_URL = "http://localhost:8080/api";
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private static final Logger logger = Logger.getLogger(App.class.getName());
    private static final String MODE = System.getenv().getOrDefault("SIMULATION_MODE", "api"); // "api" or "rabbit"
    private static final String RABBIT_HOST = "localhost";
    private static final String RABBIT_QUEUE = "item-events-queue";
    private static final String RABBIT_EXCHANGE = "item-events-exchange";

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());
            
    private static Connection rabbitConnection;
    private static Channel rabbitChannel;

    // --- START OF SIMULATION PARAMETER CHANGES ---
    private static final int NUM_ITEMS = 10;
    private static final int NUM_LOCATIONS = 10;
    private static final double LAYOUT_RADIUS = 100.0; // The radius of the circle for placing locations
    private static final double DEFAULT_LOCATION_LENGTH = 10.0; // in meters
    private static final double DEFAULT_LOCATION_SPEED = 1.0;   // in meters/second
    // --- END OF SIMULATION PARAMETER CHANGES ---


    public static void main(String[] args) {
        try {
            if (MODE.equalsIgnoreCase("rabbit")) {
                setupRabbit();
            }
            List<String> items = new ArrayList<>();
            List<String> locations = new ArrayList<>();

            // Step 1: Create Items
            logger.info("--- Creating " + NUM_ITEMS + " items ---");
            for (int i = 1; i <= NUM_ITEMS; i++) {
                String itemName = "Item" + i;
                sendEvent(new ItemCreatedEvent(itemName, itemName, 1.0, true, new HashMap<>()), "POST");
                items.add(itemName);
            }

            // --- START OF LOCATION CREATION LOGIC CHANGES ---
            // Step 2: Create Locations, spread evenly in a circle
            logger.info("--- Creating " + NUM_LOCATIONS + " locations in a circle with radius " + LAYOUT_RADIUS + " ---");
            for (int i = 0; i < NUM_LOCATIONS; i++) {
                String locationName = "Location" + (i + 1);

                // Calculate position on a circle to spread them out
                double angle = 2 * Math.PI * i / NUM_LOCATIONS;
                double latitude = LAYOUT_RADIUS * Math.sin(angle);  // y-coordinate
                double longitude = LAYOUT_RADIUS * Math.cos(angle); // x-coordinate

                logger.info(String.format("Creating %s at (x=%.2f, y=%.2f) with length=%.1f, speed=%.1f",
                        locationName, longitude, latitude, DEFAULT_LOCATION_LENGTH, DEFAULT_LOCATION_SPEED));
                
                sendEvent(new LocationCreatedEvent(
                        locationName,
                        locationName,
                        true,
                        latitude,
                        longitude,
                        DEFAULT_LOCATION_LENGTH,
                        DEFAULT_LOCATION_SPEED,
                        LocationType.CONVEYOR,
                        0,
                        new HashMap<>()
                ), "POST");
                locations.add(locationName);
            }
            // --- END OF LOCATION CREATION LOGIC CHANGES ---


            // Step 3: Establish Initial Item Positions
            logger.info("--- Setting initial item positions ---");
            for (int i = 0; i < items.size(); i++) {
                String item = items.get(i);
                String location = locations.get(i % locations.size()); // Distribute items across locations
                sendEvent(new ItemPositionChangedEvent(item, location), "POST");
            }

            // Step 4: Create Connections Between Locations to form a loop
            logger.info("--- Creating connections between locations ---");
            for (int i = 0; i < locations.size(); i++) {
                String location1 = locations.get(i);
                String location2 = locations.get((i + 1) % locations.size()); // Connect each location to the next in a circle
                createLocationConnection(location1, location2);
            }

            // --- START OF MOVEMENT SIMULATION LOGIC CHANGES ---
            // Step 5: Move Items in a Loop with calculated delay
            logger.info("--- Starting continuous item movement simulation ---");

            // Calculate the time it takes for an item to traverse one location.
            // time = distance / speed
            long moveDelayMs = (long) ((DEFAULT_LOCATION_LENGTH / DEFAULT_LOCATION_SPEED) * 1000);
            
            while (true) {
                logger.info("All items have moved. Waiting for " + (moveDelayMs / 1000.0) + " seconds for them to cross their new locations...");
                // Wait for the duration it takes for an item to travel across the conveyor
                Thread.sleep(moveDelayMs);
                
                // Rotate the location list to determine the next destination for each item
                Collections.rotate(locations, -1); // Negative rotation to move items forward in the circle

                logger.info("--- Moving all items to their next location ---");
                for (int i = 0; i < items.size(); i++) {
                    String item = items.get(i);
                    String newLocation = locations.get(i);
                    sendEvent(new ItemPositionChangedEvent(item, newLocation), "PUT");
                }
            }
            // --- END OF MOVEMENT SIMULATION LOGIC CHANGES ---

        } catch (Exception e) {
            logger.log(Level.SEVERE, "An error occurred in the simulation", e);
        }
    }
    
    private static void setupRabbit() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBIT_HOST);
        factory.setUsername("admin");
        factory.setPassword("admin");
        
        rabbitConnection = factory.newConnection();
        rabbitChannel = rabbitConnection.createChannel();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (rabbitChannel != null && rabbitChannel.isOpen()) {
                    rabbitChannel.close();
                }
                if (rabbitConnection != null && rabbitConnection.isOpen()) {
                    rabbitConnection.close();
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error closing RabbitMQ resources", e);
            }
        }));

        rabbitChannel.exchangeDeclare(RABBIT_EXCHANGE, "x-consistent-hash", true);
        logger.info("Declared consistent-hash exchange: " + RABBIT_EXCHANGE);

        rabbitChannel.queueDeclare(RABBIT_QUEUE, true, false, false, null);
        logger.info("Declared queue: " + RABBIT_QUEUE);

        rabbitChannel.queueBind(RABBIT_QUEUE, RABBIT_EXCHANGE, "1");
        logger.info("Bound queue to exchange.");
    }

    private static void sendEvent(EntityEvent event, String httpMethod) throws Exception {
        String json = objectMapper.writeValueAsString(event);
        if (MODE.equalsIgnoreCase("rabbit")) {
            String hashKey = event.getEntityId(); 
            rabbitChannel.basicPublish(RABBIT_EXCHANGE, hashKey, null, json.getBytes());
            logger.info(() -> "Sent event to RabbitMQ with hashKey=" + hashKey + ": " + json);
        } else {
            String endpoint = getEndpointForEvent(event);
            if (endpoint == null) {
                logger.warning(() -> "Unknown event type: " + event.getClass().getName());
                return;
            }

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(new URI(BASE_URL + endpoint))
                    .header("Content-Type", "application/json");

            HttpRequest.BodyPublisher bodyPublisher = HttpRequest.BodyPublishers.ofString(json);

            if ("POST".equalsIgnoreCase(httpMethod)) {
                requestBuilder.POST(bodyPublisher);
            } else if ("PUT".equalsIgnoreCase(httpMethod)) {
                requestBuilder.PUT(bodyPublisher);
            } else {
                logger.warning("Unsupported HTTP method: " + httpMethod);
                return;
            }

            HttpRequest request = requestBuilder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                // To avoid flooding the console, we log successful API calls at a finer level
                logger.log(Level.FINE, () -> "Sent event to API: " + json);
            } else {
                logger.warning(() -> "Failed to send event to API: " + response.statusCode() + " " + response.body());
            }
        }
    }

    private static String getEndpointForEvent(DomainEvent event) {
        if (event instanceof ItemCreatedEvent) {
            return "/items";
        } else if (event instanceof LocationCreatedEvent) {
            return "/locations";
        } else if (event instanceof ItemPositionChangedEvent) {
            return "/positions";
        } else if (event instanceof LocationConnectionCreatedEvent) {
            return "/connections";
        }
        return null;
    }

    private static void createLocationConnection(String locationId1, String locationId2) throws Exception {
        logger.info(() -> String.format("Creating connection event from %s to %s", locationId1, locationId2));
        LocationConnectionCreatedEvent event = new LocationConnectionCreatedEvent(locationId1, locationId2);
        sendEvent(event, "POST");
    }
}