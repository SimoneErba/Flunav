package com.fiumen.backend.models.input;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateConnection {
	private String itemId;
	private String locationId;
}
