package com.flunav.backend.models.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DisplayRuleVisualStyle {
    private String fillColor;
    private String borderColor;
    private Double borderWidth;
}
