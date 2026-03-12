package com.flunav.backend.models.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DisplayRuleColorResult {
    private Map<String, String> itemColors;
    private Map<String, String> locationColors;
    private Map<String, String> conveyorColors;
}