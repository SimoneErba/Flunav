package com.flunav.backend.e2e;

import com.redis.testcontainers.RedisContainer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.orientdb.OrientDBContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

public final class TestContainersEnvironment {

    private static final RedisContainer REDIS_CONTAINER =
            new RedisContainer(DockerImageName.parse("redis:7.0-alpine"));

    private static final ClickHouseContainer CLICKHOUSE_CONTAINER =
            new ClickHouseContainer("clickhouse/clickhouse-server:23.8")
                    .withUrlParam("allow_experimental_object_type", "1")
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource("test-clickhouse-config/users.xml"),
                            "/etc/clickhouse-server/users.d/allow_experimental.xml")
                    .withInitScripts(List.of(
                            "init-clickhouse/001_init_snapshot.sql",
                            "init-clickhouse/002_init_events.sql",
                            "init-clickhouse/003_analytics_count.sql",
                            "init-clickhouse/005_component_analytics.sql",
                            "init-clickhouse/007_item_summary.sql",
                            "init-clickhouse/004_analytics_count_mv.sql"));

    private static final OrientDBContainer ORIENTDB_CONTAINER =
            new OrientDBContainer("orientdb:3.2.0-tp3")
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource("init-orientdb/01-create-db.sh"),
                            "/docker-entrypoint-initdb.d/init.sh");

    private static final RabbitMQContainer RABBITMQ_CONTAINER =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management"))
                    .withPluginsEnabled("rabbitmq_consistent_hash_exchange");

    private TestContainersEnvironment() {
    }

    public static void start() {
        REDIS_CONTAINER.start();
        CLICKHOUSE_CONTAINER.start();
        ORIENTDB_CONTAINER.start();
        RABBITMQ_CONTAINER.start();
    }

    public static void stop() {
        RABBITMQ_CONTAINER.stop();
        ORIENTDB_CONTAINER.stop();
        CLICKHOUSE_CONTAINER.stop();
        REDIS_CONTAINER.stop();
    }

    public static Map<String, Object> springProperties(int serverPort) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("server.port", serverPort);
        properties.put("spring.data.redis.host", REDIS_CONTAINER.getHost());
        properties.put("spring.data.redis.port", REDIS_CONTAINER.getMappedPort(6379));
        properties.put("metric-snapshot.enabled", "false");

        properties.put(
                "clickhouse.url",
                String.format(
                        "http://%s:%d/default",
                        CLICKHOUSE_CONTAINER.getHost(),
                        CLICKHOUSE_CONTAINER.getMappedPort(8123)));
        properties.put("clickhouse.username", CLICKHOUSE_CONTAINER.getUsername());
        properties.put("clickhouse.password", CLICKHOUSE_CONTAINER.getPassword());

        properties.put(
                "orientdb.url",
                String.format(
                        "remote:%s:%d",
                        ORIENTDB_CONTAINER.getHost(),
                        ORIENTDB_CONTAINER.getMappedPort(2424)));
        properties.put("orientdb.username", "root");
        properties.put("orientdb.password", "root");
        properties.put("orientdb.db.name", "test-live");

        properties.put("spring.rabbitmq.host", RABBITMQ_CONTAINER.getHost());
        properties.put("spring.rabbitmq.port", RABBITMQ_CONTAINER.getAmqpPort());
        properties.put("spring.rabbitmq.username", RABBITMQ_CONTAINER.getAdminUsername());
        properties.put("spring.rabbitmq.password", RABBITMQ_CONTAINER.getAdminPassword());

        properties.put("superadmin.username", "admin");
        properties.put("superadmin.password", "Flun4v!");
        properties.put("app.demo-mode", "false");
        return properties;
    }
}
