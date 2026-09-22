package com.flunav.backend.models.multisimulation;

public enum MultiSimulationStatus {
    DRAFT,
    QUEUED,
    RUNNING,
    COMPLETED,
    COMPLETED_WITH_FAILURES,
    CANCELLING,
    CANCELLED,
    FAILED
}
