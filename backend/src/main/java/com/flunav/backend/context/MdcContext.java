package com.flunav.backend.context;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.MDC;

public final class MdcContext implements AutoCloseable {
    private final Map<String, String> previousValues = new LinkedHashMap<>();

    private MdcContext(Map<String, String> values) {
        values.forEach((key, value) -> {
            previousValues.put(key, MDC.get(key));
            if (value == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        });
    }

    public static MdcContext withValues(Map<String, String> values) {
        return new MdcContext(values);
    }

    @Override
    public void close() {
        previousValues.forEach((key, value) -> {
            if (value == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        });
    }
}
