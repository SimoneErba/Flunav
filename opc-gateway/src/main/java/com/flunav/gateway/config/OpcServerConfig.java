package com.flunav.gateway.config;

import lombok.Data;

@Data
public class OpcServerConfig {
    private String endpointUrl;
    private String username;
    private String password;
    private String securityPolicy; // Optional: None, Basic256Sha256, etc.
}