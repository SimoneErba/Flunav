package com.flunav.backend.context;

import com.flunav.backend.models.analytics.AnomalyProcessingMode;

public final class AnomalyProcessingContext {
    private static final ThreadLocal<AnomalyProcessingMode> MODE = new ThreadLocal<>();

    private AnomalyProcessingContext() {
    }

    public static ModeContext enter(AnomalyProcessingMode mode) {
        AnomalyProcessingMode previous = MODE.get();
        MODE.set(mode);
        return new ModeContext(previous);
    }

    public static AnomalyProcessingMode getMode() {
        return MODE.get();
    }

    public static final class ModeContext implements AutoCloseable {
        private final AnomalyProcessingMode previous;
        private boolean closed;

        private ModeContext(AnomalyProcessingMode previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            if (previous == null) {
                MODE.remove();
            } else {
                MODE.set(previous);
            }
            closed = true;
        }
    }
}
