package com.flunav.backend;

import com.redis.testcontainers.RedisContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.orientdb.OrientDBContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

public abstract class BaseIntegrationTest {

        static final RedisContainer REDIS_CONTAINER = new RedisContainer(DockerImageName.parse("redis:7.0-alpine"));
        static final ClickHouseContainer CLICKHOUSE_CONTAINER = new ClickHouseContainer(
                        "clickhouse/clickhouse-server:23.8").withUrlParam("allow_experimental_object_type", "1")
                        .withCopyFileToContainer(
                                        MountableFile.forClasspathResource("test-clickhouse-config/users.xml"),
                                        "/etc/clickhouse-server/users.d/allow_experimental.xml")
                        .withInitScripts(List.of("init-clickhouse/001_init_snapshot.sql",
                                        "init-clickhouse/002_init_events.sql",
                                        "init-clickhouse/003_analytics_count.sql",
                                        "init-clickhouse/005_component_analytics.sql",
                                        "init-clickhouse/007_item_summary.sql",
                                        "init-clickhouse/004_analytics_count_mv.sql"));
        static final OrientDBContainer ORIENTDB_CONTAINER = new OrientDBContainer("orientdb:3.2.0-tp3")
                        .withCopyFileToContainer(MountableFile.forClasspathResource("init-orientdb/01-create-db.sh"),
                                        "/docker-entrypoint-initdb.d/init.sh");

        static {
                REDIS_CONTAINER.start();
                CLICKHOUSE_CONTAINER.start();
                ORIENTDB_CONTAINER.start();

        }

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
                registry.add("spring.data.redis.host", REDIS_CONTAINER::getHost);
                registry.add("spring.data.redis.port", () -> REDIS_CONTAINER.getMappedPort(6379));

                registry.add("clickhouse.url",
                                () -> String.format("http://%s:%d/default", CLICKHOUSE_CONTAINER.getHost(),
                                                CLICKHOUSE_CONTAINER.getMappedPort(8123)));
                registry.add("clickhouse.username", CLICKHOUSE_CONTAINER::getUsername);
                registry.add("clickhouse.password", CLICKHOUSE_CONTAINER::getPassword);

                registry.add("orientdb.url", () -> String.format("remote:%s:%d", ORIENTDB_CONTAINER.getHost(),
                                ORIENTDB_CONTAINER.getMappedPort(2424)));
                registry.add("orientdb.username", () -> "root");
                registry.add("orientdb.password", () -> "root");
                registry.add("orientdb.db.name", () -> "test-sim");
        }
}
