package com.flunav.backend.models.response;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ThroughputMetric {
    private Instant timestamp;
    private long itemsEntered;
    private long itemsExited;
    private long segmentsProcessed;
}