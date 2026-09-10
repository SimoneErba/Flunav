package com.flunav.backend.models.simulation;

/** Runtime provenance; it is deliberately not part of the stored domain contract. */
public enum EventOrigin {
    EXTERNAL_INGESTION, LIVE_SCHEDULED, REPLAY, SIMULATION_GENERATED
}
