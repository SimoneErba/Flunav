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
    private final OperatorType secondOperator;
    private final String secondValue;
    private final List<String> destinations;
    private final Instant validFrom;
    private final Instant rushAt;
    private final Instant validTo;

    @JsonCreator
    public DestinationMappingRecord(
            @JsonProperty("fieldName") String fieldName,
            @JsonProperty("dataType") DataType dataType,
            @JsonProperty("operator") OperatorType operator,
            @JsonProperty("value") String value,
            @JsonProperty("secondOperator") OperatorType secondOperator,
            @JsonProperty("secondValue") String secondValue,
            @JsonProperty("destinations") List<String> destinations,
            @JsonProperty("validFrom") Instant validFrom,
            @JsonProperty("rushAt") Instant rushAt,
            @JsonProperty("validTo") Instant validTo) {
        this.fieldName = fieldName;
        this.dataType = dataType;
        this.operator = operator;
        this.value = value;
        this.secondOperator = secondOperator;
        this.secondValue = secondValue;
        this.destinations = destinations;
        this.validFrom = validFrom;
        this.rushAt = rushAt;
        this.validTo = validTo;
    }

    public DestinationMappingRecord(
            String fieldName,
            DataType dataType,
            OperatorType operator,
            String value,
            OperatorType secondOperator,
            String secondValue,
            List<String> destinations,
            Instant validFrom,
            Instant validTo) {
        this(fieldName, dataType, operator, value, secondOperator, secondValue, destinations, validFrom, null, validTo);
    }

    public DestinationMappingRecord(
            String value,
            List<String> destinations,
            Instant validFrom,
            Instant validTo) {
        this(null, null, null, value, null, null, destinations, validFrom, null, validTo);
    }

    public DestinationMappingRecord(
            String fieldName,
            DataType dataType,
            OperatorType operator,
            String value,
            List<String> destinations,
            Instant validFrom,
            Instant validTo) {
        this(fieldName, dataType, operator, value, null, null, destinations, validFrom, null, validTo);
    }
}
