package flunav.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

import java.time.Instant;
import java.util.List;

import flunav.types.DataType;
import flunav.types.OperatorType;

@Getter
public class DestinationMappingRecord {
    private final String fieldName;
    private final DataType dataType;
    private final OperatorType operator;
    private final String value;
    private final List<String> destinations;
    private final Instant validFrom;
    private final Instant validTo;

    @JsonCreator
    public DestinationMappingRecord(
            @JsonProperty("fieldName") String fieldName,
            @JsonProperty("dataType") DataType dataType,
            @JsonProperty("operator") OperatorType operator,
            @JsonProperty("value") String value,
            @JsonProperty("destinations") List<String> destinations,
            @JsonProperty("validFrom") Instant validFrom,
            @JsonProperty("validTo") Instant validTo) {
        this.fieldName = fieldName;
        this.dataType = dataType;
        this.operator = operator;
        this.value = value;
        this.destinations = destinations;
        this.validFrom = validFrom;
        this.validTo = validTo;
    }

    public DestinationMappingRecord(
            String value,
            List<String> destinations,
            Instant validFrom,
            Instant validTo) {
        this(null, null, null, value, destinations, validFrom, validTo);
    }
}
