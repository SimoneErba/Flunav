package com.flunav.backend.models.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class BadActorMetric {
    private String locationId;
    private String locationName;
    private Long errorCount;
    private Double downtimeMinutes;
}