package com.flunav.backend.models.response;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ItemJourney {
    private String sourceId;
    private String targetId;
    private double progress;
    private Instant startTime;
}