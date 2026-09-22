package com.flunav.backend.models.multisimulation;

public record ArrivalConfiguration(
        double ratePerHour,
        ArrivalDistribution distribution,
        double rateVariationPercent) {
}
