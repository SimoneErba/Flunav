package com.fiumen.backend.models.response;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ItemResponse {
    private String id;
    private String name;
    private Double speed;
    private Boolean active;
    private Map<String, Object> properties;
} 