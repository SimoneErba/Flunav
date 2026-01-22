package com.flunav.backend.services;

import com.flunav.backend.models.response.ThroughputMetric;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsNotificationService {

    private final ClickHouseService clickHouseService;
    private final WebSocketService webSocketService;

    public AnalyticsNotificationService(ClickHouseService clickHouseService, WebSocketService webSocketService) {
        this.clickHouseService = clickHouseService;
        this.webSocketService = webSocketService;
    }

    @Scheduled(fixedRate = 5000)
    public void pushLiveAnalytics() {
        ThroughputMetric latestMetric = clickHouseService.getLatestThroughput();

        webSocketService.broadcastLiveAnalytic(latestMetric);
    }
}