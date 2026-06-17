package flunav.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import flunav.types.DisplayRule;
import lombok.Getter;

import java.time.Instant;
import java.util.List;

@Getter
public class MapDisplayRulesEvent extends DomainEvent {
    private final List<DisplayRule> rules;

    @JsonCreator
    public MapDisplayRulesEvent(
            @JsonProperty("rules") List<DisplayRule> rules,
            @JsonProperty("timestamp") Instant timestamp) {
        super("MAP_DISPLAY_RULES", timestamp);
        this.rules = rules;
    }

    public MapDisplayRulesEvent(List<DisplayRule> rules) {
        this(rules, null);
    }
}
