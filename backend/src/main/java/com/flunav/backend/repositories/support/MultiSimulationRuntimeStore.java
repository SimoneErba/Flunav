package com.flunav.backend.repositories.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.flunav.backend.context.DatabaseContextHolder;

/**
 * Owns the Redis-equivalent state of short-lived multi-simulation runs.
 * Ordinary live and interactive simulation contexts are deliberately absent from
 * this registry and continue to use Redis through their existing repositories.
 */
@Component
public class MultiSimulationRuntimeStore {
    private final Map<String, State> states = new ConcurrentHashMap<>();

    public State register(String simulationId) {
        State state = new State();
        State previous = states.putIfAbsent(simulationId, state);
        if (previous != null) {
            throw new IllegalStateException("Multi-simulation runtime already registered: " + simulationId);
        }
        return state;
    }

    public State current() {
        String simulationId = DatabaseContextHolder.getSimulationId();
        return simulationId == null ? null : states.get(simulationId);
    }

    public State get(String simulationId) {
        return simulationId == null ? null : states.get(simulationId);
    }

    public boolean contains(String simulationId) {
        return simulationId != null && states.containsKey(simulationId);
    }

    public void remove(String simulationId) {
        State state = states.remove(simulationId);
        if (state != null) {
            state.clear();
        }
    }

    public static final class State {
        private final Map<String, String> values = new ConcurrentHashMap<>();
        private final Map<String, Map<String, String>> hashes = new ConcurrentHashMap<>();
        private final Map<String, Set<String>> sets = new ConcurrentHashMap<>();
        private final Map<String, Map<String, Double>> sortedSets = new ConcurrentHashMap<>();

        public String getValue(String key) {
            return values.get(key);
        }

        public void setValue(String key, String value) {
            if (value == null) values.remove(key); else values.put(key, value);
        }

        public boolean setValueIfAbsent(String key, String value) {
            return values.putIfAbsent(key, value) == null;
        }

        public long increment(String key, long delta) {
            synchronized (values) {
                long next = Long.parseLong(values.getOrDefault(key, "0")) + delta;
                values.put(key, Long.toString(next));
                return next;
            }
        }

        public String hashGet(String key, String field) {
            Map<String, String> hash = hashes.get(key);
            return hash == null ? null : hash.get(field);
        }

        public Map<String, String> hashEntries(String key) {
            Map<String, String> hash = hashes.get(key);
            return hash == null ? Map.of() : Map.copyOf(hash);
        }

        public List<String> hashValues(String key) {
            Map<String, String> hash = hashes.get(key);
            return hash == null ? List.of() : List.copyOf(hash.values());
        }

        public void hashPut(String key, String field, String value) {
            hashes.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>()).put(field, value);
        }

        public void hashPutAll(String key, Map<String, String> values) {
            hashes.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>()).putAll(values);
        }

        public void hashDelete(String key, String... fields) {
            Map<String, String> hash = hashes.get(key);
            if (hash == null) return;
            for (String field : fields) hash.remove(field);
            if (hash.isEmpty()) hashes.remove(key, hash);
        }

        public void setAdd(String key, String value) {
            sets.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet()).add(value);
        }

        public void setRemove(String key, String value) {
            Set<String> set = sets.get(key);
            if (set == null) return;
            set.remove(value);
            if (set.isEmpty()) sets.remove(key, set);
        }

        public Set<String> setMembers(String key) {
            Set<String> set = sets.get(key);
            return set == null ? Set.of() : Set.copyOf(set);
        }

        public long setSize(String key) {
            Set<String> set = sets.get(key);
            return set == null ? 0 : set.size();
        }

        public void sortedSetAdd(String key, String member, double score) {
            sortedSets.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>()).put(member, score);
        }

        public void sortedSetRemove(String key, String member) {
            Map<String, Double> set = sortedSets.get(key);
            if (set == null) return;
            set.remove(member);
            if (set.isEmpty()) sortedSets.remove(key, set);
        }

        public Set<String> sortedSetRange(String key, long start, long end) {
            List<String> ordered = ordered(key, false, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
            if (ordered.isEmpty()) return Set.of();
            int from = normalize(start, ordered.size());
            int through = end < 0 ? ordered.size() - 1 : Math.min((int) end, ordered.size() - 1);
            if (from > through || from >= ordered.size()) return Set.of();
            return new LinkedHashSet<>(ordered.subList(from, through + 1));
        }

        public Set<String> sortedSetRangeByScore(String key, double minimum, double maximum, boolean reverse) {
            return new LinkedHashSet<>(ordered(key, reverse, minimum, maximum));
        }

        public long sortedSetSize(String key) {
            Map<String, Double> set = sortedSets.get(key);
            return set == null ? 0 : set.size();
        }

        public void sortedSetRemoveByScore(String key, double minimum, double maximum) {
            Map<String, Double> set = sortedSets.get(key);
            if (set == null) return;
            set.entrySet().removeIf(entry -> entry.getValue() >= minimum && entry.getValue() <= maximum);
            if (set.isEmpty()) sortedSets.remove(key, set);
        }

        public void delete(String... keys) {
            for (String key : keys) {
                values.remove(key);
                hashes.remove(key);
                sets.remove(key);
                sortedSets.remove(key);
            }
        }

        public void delete(Collection<String> keys) {
            delete(keys.toArray(String[]::new));
        }

        public void deleteMatching(String prefix) {
            values.keySet().removeIf(key -> key.startsWith(prefix));
            hashes.keySet().removeIf(key -> key.startsWith(prefix));
            sets.keySet().removeIf(key -> key.startsWith(prefix));
            sortedSets.keySet().removeIf(key -> key.startsWith(prefix));
        }

        public void rename(String source, String target) {
            String value = values.remove(source);
            if (value != null) values.put(target, value);
            Map<String, String> hash = hashes.remove(source);
            if (hash != null) hashes.put(target, hash);
            Set<String> set = sets.remove(source);
            if (set != null) sets.put(target, set);
            Map<String, Double> sorted = sortedSets.remove(source);
            if (sorted != null) sortedSets.put(target, sorted);
        }

        private List<String> ordered(String key, boolean reverse, double minimum, double maximum) {
            Map<String, Double> set = sortedSets.get(key);
            if (set == null) return List.of();
            Comparator<Map.Entry<String, Double>> comparator = Comparator
                    .comparingDouble(Map.Entry<String, Double>::getValue)
                    .thenComparing(Map.Entry::getKey);
            if (reverse) comparator = comparator.reversed();
            List<String> result = new ArrayList<>();
            set.entrySet().stream()
                    .filter(entry -> entry.getValue() >= minimum && entry.getValue() <= maximum)
                    .sorted(comparator)
                    .map(Map.Entry::getKey)
                    .forEach(result::add);
            return result;
        }

        private int normalize(long index, int size) {
            if (index >= 0) return (int) Math.min(index, size);
            return Math.max(0, size + (int) index);
        }

        private void clear() {
            values.clear();
            hashes.clear();
            sets.clear();
            sortedSets.clear();
        }
    }
}
