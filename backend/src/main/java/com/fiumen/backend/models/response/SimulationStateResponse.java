package com.fiumen.backend.models.response;
import com.fiumen.backend.models.simulation.SimulationStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SimulationStateResponse {

    private final String id;
    private SimulationStatus status;
}