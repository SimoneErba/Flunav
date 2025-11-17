package com.fiumen.backend.models.response;

import java.util.Map;

import fiumen.types.LocationType;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LocationResponse {
    private String id;
    private String name;
    private Double latitude;
    private Double longitude;
    private Double length;
    private Double speed;
    private LocationType type;
    private Boolean active;
    private Boolean isMainPath;
    private Integer capacity;
    private Map<String, Object> properties;
    private List<ItemResponse> items;
    private List<ConnectionResponse> connections;
}