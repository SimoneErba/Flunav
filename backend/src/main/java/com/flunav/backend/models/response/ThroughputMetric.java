package com.flunav.backend.models.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ThroughputMetric {
    private Instant timestamp;
    private long itemsEntered;
    private long itemsExited;
    private long itemsCurrent;
    private int bucketSeconds;
    private Long segmentsProcessed;

    public ThroughputMetric(Instant timestamp, long itemsEntered, long itemsExited, long itemsCurrent,
            int bucketSeconds) {
        this(timestamp, itemsEntered, itemsExited, itemsCurrent, bucketSeconds, null);
    }

    public ThroughputMetric(Instant timestamp, long itemsEntered, long itemsExited, long itemsCurrent,
            int bucketSeconds, Long segmentsProcessed) {
        this.timestamp = timestamp;
        this.itemsEntered = itemsEntered;
        this.itemsExited = itemsExited;
        this.itemsCurrent = itemsCurrent;
        this.bucketSeconds = bucketSeconds;
        this.segmentsProcessed = segmentsProcessed;
    }
}
