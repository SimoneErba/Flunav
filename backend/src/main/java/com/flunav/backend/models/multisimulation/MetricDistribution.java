package com.flunav.backend.models.multisimulation;

public record MetricDistribution(
        long sampleCount,
        double mean,
        double median,
        double standardDeviation,
        double minimum,
        double maximum,
        double p5,
        double p25,
        double p75,
        double p95) {
}
