package com.flunav.backend.models.input;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConnectionInput {
	private String location1Id;
	private String location2Id;
}
