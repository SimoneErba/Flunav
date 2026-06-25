package flunav.types;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DisplayRule {
    private String fieldName;
    private DataType dataType;
    private OperatorType operator;
    private Object value;
    private OperatorType secondOperator;
    private Object secondValue;
    private String color;
    private String borderColor;
    private Double borderWidth;
    private Integer priority;

    public DisplayRule(String fieldName, DataType dataType, OperatorType operator, Object value,
            String color, Integer priority) {
        this(fieldName, dataType, operator, value, null, null, color, null, null, priority);
    }
}
