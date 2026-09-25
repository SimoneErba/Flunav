package com.flunav.backend.repositories;

import java.util.Optional;
import java.util.Map;
import java.util.HashMap;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flunav.backend.context.SimulationBuildCacheContext;
import com.flunav.backend.domain.Item;
import com.flunav.backend.repositories.support.RedisKeyNamespace;

/** Complete item metadata for one build, held in a single namespaced Redis hash. */
@Repository
public class SimulationItemMetadataRepository {
    private static final String KEY = "build_item_metadata";
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public SimulationItemMetadataRepository(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public Optional<Item> get(String id) {
        if (!SimulationBuildCacheContext.enabled()) {
            return Optional.empty();
        }
        if (SimulationBuildCacheContext.localItemMetadata(id) instanceof Metadata local) {
            return Optional.of(toItem(id, local));
        }
        Object raw = redis.opsForHash().get(RedisKeyNamespace.current(KEY), id);
        if (!(raw instanceof String json)) {
            return Optional.empty();
        }
        try {
            Metadata metadata = mapper.readValue(json, Metadata.class);
            SimulationBuildCacheContext.cacheLocalItemMetadata(id, metadata);
            return Optional.of(toItem(id, metadata));
        } catch (JsonProcessingException failure) {
            evict(id);
            return Optional.empty();
        }
    }

    public void put(Item item) {
        if (!SimulationBuildCacheContext.enabled() || item == null) {
            return;
        }
        try {
            Metadata metadata = new Metadata(item.getName(), item.isActive(), item.getPriority(),
                    item.getProperties());
            redis.opsForHash().put(RedisKeyNamespace.current(KEY), item.getId(),
                    mapper.writeValueAsString(metadata));
            SimulationBuildCacheContext.cacheLocalItemMetadata(item.getId(), metadata);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Could not cache item metadata for " + item.getId(), failure);
        }
    }

    public void evict(String id) {
        if (SimulationBuildCacheContext.enabled()) {
            SimulationBuildCacheContext.evictLocalItemMetadata(id);
            redis.opsForHash().delete(RedisKeyNamespace.current(KEY), id);
        }
    }

    public void clear(String simulationId) {
        if (simulationId != null) {
            redis.delete(RedisKeyNamespace.simulation(simulationId, KEY));
        }
    }

    private record Metadata(String name, boolean active, Double priority, Map<String, Object> properties) {
        private Metadata {
            properties = properties == null ? null : new HashMap<>(properties);
        }
    }

    private Item toItem(String id, Metadata metadata) {
        return new Item(id, metadata.name(), metadata.active(), metadata.priority(),
                metadata.properties() == null ? null : new HashMap<>(metadata.properties()));
    }
}
