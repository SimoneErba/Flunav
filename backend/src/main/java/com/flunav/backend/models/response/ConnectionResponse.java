package com.flunav.backend.models.response;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConnectionResponse {
    private String sourceId;
    private String targetId;
    private String direction; // Direction: "in" or "out"
    private Map<String, Object> properties;
}