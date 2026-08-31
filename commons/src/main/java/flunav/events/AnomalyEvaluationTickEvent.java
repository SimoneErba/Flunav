package flunav.events;

import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

@Getter
public final class AnomalyEvaluationTickEvent extends DomainEvent {
    public enum Cadence {
        FAST,
        MINUTE,
        BASELINE
    }

    private final Cadence cadence;

    @JsonCreator
    public AnomalyEvaluationTickEvent(
            @JsonProperty("cadence") Cadence cadence,
            @JsonProperty("timestamp") Instant timestamp) {
        super("ANOMALY_EVALUATION_TICK", timestamp);
        this.cadence = Objects.requireNonNull(cadence, "cadence is required");
    }
}
