package com.flunav.backend.test;

import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.utility.MountableFile;

public final class ClickHouseTestContainerFactory {

    private ClickHouseTestContainerFactory() {
    }

    public static ClickHouseContainer create() {
        return createSnapshotContainer()
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("init-clickhouse/002_init_events.sql"),
                        "/docker-entrypoint-initdb.d/002_init_events.sql")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("init-clickhouse/003_analytics_count.sh", 0744),
                        "/docker-entrypoint-initdb.d/003_analytics_count.sh")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("init-clickhouse/005_component_analytics.sql"),
                        "/docker-entrypoint-initdb.d/005_component_analytics.sql")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("init-clickhouse/007_item_summary.sql"),
                        "/docker-entrypoint-initdb.d/007_item_summary.sql")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("init-clickhouse/010_logs.sql"),
                        "/docker-entrypoint-initdb.d/010_logs.sql")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("init-clickhouse/004_analytics_count_mv.sql"),
                        "/docker-entrypoint-initdb.d/011_analytics_count_mv.sql");
    }

    public static ClickHouseContainer createSnapshotContainer() {
        return new ClickHouseContainer("clickhouse/clickhouse-server:25.3")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("init-clickhouse/001_init_snapshot.sql"),
                        "/docker-entrypoint-initdb.d/001_init_snapshot.sql");
    }
}
