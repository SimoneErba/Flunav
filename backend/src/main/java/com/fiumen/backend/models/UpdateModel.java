package com.fiumen.backend.models;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateModel {
	private String id;
	private Map<String, Object> properties;
}
