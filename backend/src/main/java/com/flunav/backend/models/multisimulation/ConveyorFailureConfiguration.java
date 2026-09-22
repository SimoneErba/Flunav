package com.flunav.backend.models.multisimulation;

public record ConveyorFailureConfiguration(
        String conveyorId,
        double failuresPerHour,
        Long repairDurationSeconds) {
}
