package com.flumen.backend.models.response;
import com.flumen.backend.models.simulation.SimulationStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SimulationStateResponse {

    private final String id;
    private SimulationStatus status;
}