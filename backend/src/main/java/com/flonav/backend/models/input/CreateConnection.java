package com.flonav.backend.models.input;

import flonav.types.PositionType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateConnection {
	private String itemId;
	private String locationId;
	private Double progress;
}