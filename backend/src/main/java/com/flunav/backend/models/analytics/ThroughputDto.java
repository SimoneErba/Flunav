package com.flunav.backend.models.analytics;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ThroughputDto {

    public String ts;
    public long entered;
    public long exited;
    public long segments;

    public ThroughputDto() {
        // required by Jackson
    }
}