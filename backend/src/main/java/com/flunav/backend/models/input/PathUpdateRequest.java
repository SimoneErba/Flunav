package com.flunav.backend.models.input;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record PathUpdateRequest(
        @ArraySchema(
                schema = @Schema(type = "string"),
                arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
        List<?> path) {
}
