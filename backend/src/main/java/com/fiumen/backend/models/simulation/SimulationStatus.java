package com.fiumen.backend.models.simulation;

public enum SimulationStatus {
    QUEUED,     // Waiting for resources to start building.
    BUILDING,   // The historical graph is being created.
    READY,      // Built and ready to be played.
    PLAYING,    // Actively replaying events.
    PAUSED,     // Temporarily suspended by the user.
    COMPLETED,  // Finished playing all events naturally.
    STOPPED,    // Manually stopped by the user before completion.
    FAILED      // An unrecoverable error occurred.
}