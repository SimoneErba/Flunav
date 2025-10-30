package com.fiumen.backend.config;

import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class StatusMappingConfig {
    private int customerStatusCode;
    private String customerStatusName;
    private String internalStatus;
    private DisplayConfig display;
}