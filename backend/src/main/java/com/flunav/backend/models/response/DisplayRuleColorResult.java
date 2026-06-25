package com.flunav.backend.models.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DisplayRuleColorResult {
    private Map<String, DisplayRuleVisualStyle> itemStyles;
    private Map<String, DisplayRuleVisualStyle> locationStyles;
    private Map<String, DisplayRuleVisualStyle> conveyorStyles;
}
