package flunav.simulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import flunav.events.ConnectionActivatedEvent;
import flunav.events.ConnectionCreatedEvent;
import flunav.events.ConnectionDeactivatedEvent;
import flunav.events.ConnectionDeletedEvent;
import flunav.events.ConnectionPropertiesUpdatedEvent;
import flunav.events.DomainEvent;
import flunav.events.EntityEvent;
import flunav.events.ItemCreatedEvent;
import flunav.events.ItemDeletedEvent;
import flunav.events.ItemDestinationEvent;
import flunav.events.ItemPositionChangedEvent;
import flunav.events.LocationCreatedEvent;
import flunav.events.LocationDeletedEvent;
import flunav.types.ConveyorType;
import flunav.types.LocationType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class SimulatorUtils {
    private static final String BASE_URL = System.getenv().getOrDefault("BASE_URL", "http://localhost:8080/api");
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    public static final Logger logger = Logger.getLogger(App.class.getName());

    public static final String MODE = System.getenv().getOrDefault("SIMULATION_MODE", "api");
    public static final String ACTION = System.getenv().getOrDefault("SIMULATION_ACTION", "setup");

    private static final String RABBIT_HOST = System.getenv().getOrDefault("RABBIT_HOST", "localhost");
    private static final String RABBIT_QUEUE = "item-events-queue";
    private static final String RABBIT_EXCHANGE = "item-events-exchange";

    private static final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private static Connection rabbitConnection;
    private static Channel rabbitChannel;
    public static boolean quietEventLogs = false;

    public static final AtomicLong itemCounter = new AtomicLong(0);

    private SimulatorUtils() {
    }

    public static void setupRabbit() throws Exception {
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
        rabbitChannel.queueDeclare(RABBIT_QUEUE, true, false, false, null);
        rabbitChannel.queueBind(RABBIT_QUEUE, RABBIT_EXCHANGE, "1");
        logger.info("RabbitMQ setup complete.");
    }

    public static void sendEvent(DomainEvent event, String httpMethod) throws Exception {
        String json = objectMapper.writeValueAsString(event);
        if (MODE.equalsIgnoreCase("rabbit")) {
            String hashKey = event instanceof EntityEvent ? ((EntityEvent) event).getEntityId() : "domainEvent";
            rabbitChannel.basicPublish(RABBIT_EXCHANGE, hashKey, null, json.getBytes());
            if (!quietEventLogs) {
                logger.info(() -> "Sent event to RabbitMQ with hashKey=" + hashKey + ": " + json);
            }
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
        if (event instanceof ItemCreatedEvent) {
            return "/items";
        }
        if (event instanceof LocationCreatedEvent) {
            return "/locations";
        }
        if (event instanceof ItemPositionChangedEvent) {
            return "/positions";
        }
        if (event instanceof ConnectionCreatedEvent) {
            return "/conveyors";
        }
        if (event instanceof ItemDestinationEvent) {
            return "/items";
        }
        if (event instanceof ConnectionActivatedEvent) {
            return "/conveyors";
        }
        if (event instanceof ConnectionDeactivatedEvent) {
            return "/conveyors";
        }
        if (event instanceof ConnectionPropertiesUpdatedEvent) {
            return "/conveyors";
        }

        if (event instanceof ItemDeletedEvent) {
            return "/items/" + ((EntityEvent) event).getEntityId();
        }
        if (event instanceof LocationDeletedEvent) {
            return "/locations/" + ((EntityEvent) event).getEntityId();
        }
        if (event instanceof ConnectionDeletedEvent) {
            return "/conveyors";
        }

        return null;
    }

    public static void createLocation(String id, double lat, double lon, LocationType type) throws Exception {
        int capacity = type == LocationType.CHUTE ? 1000 : 0;
        createLocation(id, lat, lon, type, capacity);
    }

    public static void createLocation(String id, double lat, double lon, LocationType type, int capacity)
            throws Exception {
        sendEvent(new LocationCreatedEvent(id, id, true, lat, lon, type, capacity, new HashMap<>()), "POST");
    }

    public static void createConveyor(String from, String to, double length, double speed, boolean mainPath)
            throws Exception {
        createConveyor(from, to, length, speed, mainPath, true);
    }

    public static void createConveyor(String from, String to, double length, double speed, boolean mainPath,
            boolean logCreation) throws Exception {
        if (logCreation) {
            logger.info(() -> String.format("Creating conveyor from %s to %s [Len: %.1f, Spd: %.1f]",
                    from, to, length, speed));
        }
        long timeToTraverse = (long) ((length / speed) * 1000);
        ConnectionCreatedEvent event = new ConnectionCreatedEvent(
                "Conveyor_" + from + "_" + to, from, to, length, speed, 0.0, timeToTraverse, mainPath,
                "Conveyor_" + from + "_" + to, true, ConveyorType.BELT, 0, new HashMap<>());
        sendEvent(event, "POST");
    }

    public static void deleteLocation(String id) throws Exception {
        logger.info("Deleting Location: " + id);
        sendEvent(new LocationDeletedEvent(id), "DELETE");
    }

    public static void deleteConveyor(String from, String to) throws Exception {
        logger.info("Deleting Conveyor: " + from + " -> " + to);
        sendEvent(new ConnectionDeletedEvent(from, to), "DELETE");
    }
}
