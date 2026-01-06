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
    private String color;
    private Integer priority;
}