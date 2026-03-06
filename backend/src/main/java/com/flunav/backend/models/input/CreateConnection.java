package com.flunav.backend.models.input;

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