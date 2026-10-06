package com.flunav.backend.config;

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class ClickHouseSchedulingConfig {
    @Bean
    @Primary
    public ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.build();
    }

    /** Keeps analytics flushes running while anomaly evaluations recover missed boundaries. */
    @Bean
    public ThreadPoolTaskScheduler clickHouseAnalyticsScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("clickhouse-analytics-flush-");
        return scheduler;
    }
}
