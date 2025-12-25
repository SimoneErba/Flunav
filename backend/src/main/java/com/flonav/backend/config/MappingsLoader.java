package com.flonav.backend.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.Setter;

@Configuration
@ConfigurationProperties(prefix = "")
@Getter
@Setter
public class MappingsLoader {
    private List<StatusMappingConfig> statusMappings;
}
