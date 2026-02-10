package com.flunav.backend.repositories;

import com.flunav.backend.context.DatabaseContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.*;

@Repository
public class LiveConveyorRepository {
    private static final long DEFAULT_TTL_HOURS = 1;

    private final StringRedisTemplate redis;

    public LiveConveyorRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void addItemToConveyor(String conveyorId, String itemId) {
        String key = getNamespacedKey(conveyorId + ":items");
        redis.opsForSet().add(key, itemId);
        redis.expire(key, Duration.ofHours(DEFAULT_TTL_HOURS));
    }

    public void removeItemFromConveyor(String conveyorId, String itemId) {
        String key = getNamespacedKey(conveyorId + ":items");
        redis.opsForSet().remove(key, itemId);
    }

    public Set<String> getItemsOnConveyor(String conveyorId) {
        String key = getNamespacedKey(conveyorId + ":items");
        return redis.opsForSet().members(key);
    }

    public void updateTailPosition(String conveyorId, double tailMeters) {
        String key = getNamespacedKey(conveyorId + ":tail");
        redis.opsForValue().set(key, String.valueOf(tailMeters));
    }

    public Double getTailPosition(String conveyorId) {
        String key = getNamespacedKey(conveyorId + ":tail");
        String val = redis.opsForValue().get(key);
        return val != null ? Double.parseDouble(val) : null;
    }

    public void incrementChuteOccupancy(String chuteId) {
        String key = getNamespacedKey("chute:" + chuteId + ":occupancy");
        redis.opsForValue().increment(key);
    }

    public void decrementChuteOccupancy(String chuteId) {
        String key = getNamespacedKey("chute:" + chuteId + ":occupancy");
        redis.opsForValue().decrement(key);
    }

    public void clearChuteOccupancy(String chuteId) {
        String key = getNamespacedKey("chute:" + chuteId + ":occupancy");
        redis.delete(key);
    }

    public Integer getChuteOccupancy(String chuteId) {
        String key = getNamespacedKey("chute:" + chuteId + ":occupancy");
        String val = redis.opsForValue().get(key);
        return val != null ? Integer.parseInt(val) : 0;
    }

    public void deleteConveyor(String conveyorId) {
        String itemsKey = getNamespacedKey(conveyorId + ":items");
        String tailKey = getNamespacedKey(conveyorId + ":tail");
        redis.delete(List.of(itemsKey, tailKey));
    }

    public void cleanupSimulationData(String simulationId) {
        String pattern = "sim:" + simulationId + ":conv:*";
        Set<String> keys = redis.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private String getNamespacedKey(String baseKey) {
        String simId = DatabaseContextHolder.getSimulationId();
        if (simId != null) {
            return "sim:" + simId + ":conv:" + baseKey;
        }
        return "conv:" + baseKey;
    }
}