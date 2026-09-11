package com.flunav.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.flunav.backend.models.analytics.AnomalyDetectorType;
import com.flunav.backend.models.analytics.AnomalyProcessingMode;
import com.flunav.backend.models.analytics.DetectorBaseline;
import com.flunav.backend.models.analytics.LocationFlowObservation;
import com.flunav.backend.models.analytics.TransitObservation;
import com.flunav.backend.models.input.LocationInput;
import com.flunav.backend.repositories.AnomalyObservationRepository;
import com.flunav.backend.repositories.LiveLocationRepository;
import com.flunav.backend.services.AnomalyEngine;
import com.flunav.backend.services.LocationService;

import flunav.events.AnomalyEvaluationTickEvent;
import flunav.types.LocationType;

@SpringBootTest(properties = {
        "springwolf.enabled=false",
        "state-recovery.enabled=false",
        "graph-snapshot.enabled=false",
        "metric-snapshot.enabled=false",
        "anomaly.live-scheduler.enabled=false",
        "anomaly.cadence.fast-seconds=10"
})
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AnomalyEngineIntegrationTests extends BaseIntegrationTest {
    private final AnomalyEngine anomalyEngine;
    private final AnomalyObservationRepository anomalyRepository;
    private final LocationService locationService;
    private final LiveLocationRepository liveLocationRepository;
    private final StringRedisTemplate redis;

    AnomalyEngineIntegrationTests(AnomalyEngine anomalyEngine, AnomalyObservationRepository anomalyRepository,
            LocationService locationService, LiveLocationRepository liveLocationRepository,
            StringRedisTemplate redis) {
        this.anomalyEngine = anomalyEngine;
        this.anomalyRepository = anomalyRepository;
        this.locationService = locationService;
        this.liveLocationRepository = liveLocationRepository;
        this.redis = redis;
    }

    @BeforeEach
    void resetAnomalyState() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    void fullOccupancyIsCollectedImmediatelyButDetectedOnlyOnDueTicks() {
        String suffix = UUID.randomUUID().toString();
        String locationId = "anomaly-jam-" + suffix;
        locationService.createLocation(new LocationInput(locationId, locationId, 0.0, 0.0,
                null, null, LocationType.CHUTE, 1, true, false, Map.of()));
        liveLocationRepository.addItemToLocation(locationId, "item-" + suffix);

        assertTrue(anomalyRepository.getFindings(Instant.EPOCH, Instant.now().plusSeconds(3600)).stream()
                .noneMatch(finding -> locationId.equals(finding.componentId())));

        Instant previous = anomalyRepository.getLastBoundary(AnomalyEvaluationTickEvent.Cadence.FAST);
        Instant first = anomalyEngine.nextBoundary(previous != null ? previous : Instant.now(),
                AnomalyEvaluationTickEvent.Cadence.FAST);
        assertTrue(anomalyEngine.evaluate(new AnomalyEvaluationTickEvent(
                AnomalyEvaluationTickEvent.Cadence.FAST, first), AnomalyProcessingMode.HISTORICAL_BUILD).stream()
                .noneMatch(finding -> locationId.equals(finding.componentId())));

        var findings = anomalyEngine.evaluate(new AnomalyEvaluationTickEvent(
                AnomalyEvaluationTickEvent.Cadence.FAST,
                anomalyEngine.nextBoundary(first, AnomalyEvaluationTickEvent.Cadence.FAST)),
                AnomalyProcessingMode.HISTORICAL_BUILD);
        assertEquals(1, findings.stream()
                .filter(finding -> locationId.equals(finding.componentId()))
                .filter(finding -> finding.detector() == AnomalyDetectorType.OCCUPANCY_JAM)
                .count());
        assertTrue(findings.stream().filter(finding -> locationId.equals(finding.componentId()))
                .allMatch(finding -> finding.alarmState().name().equals("NOT_APPLICABLE")));
    }

    @Test
    void departureResetsTheConsecutiveJamCounterEvenWhenOccupancyRemainsFull() {
        String suffix = UUID.randomUUID().toString();
        String locationId = "anomaly-departure-" + suffix;
        locationService.createLocation(new LocationInput(locationId, locationId, 0.0, 0.0,
                null, null, LocationType.CHUTE, 1, true, false, Map.of()));
        liveLocationRepository.addItemToLocation(locationId, "item-" + suffix);

        Instant first = nextTick(AnomalyEvaluationTickEvent.Cadence.FAST);
        anomalyEngine.evaluate(new AnomalyEvaluationTickEvent(AnomalyEvaluationTickEvent.Cadence.FAST, first),
                AnomalyProcessingMode.HISTORICAL_BUILD);
        Instant second = anomalyEngine.nextBoundary(first, AnomalyEvaluationTickEvent.Cadence.FAST);
        anomalyRepository.appendFlow(new LocationFlowObservation("departure-" + suffix, "item-" + suffix,
                locationId, LocationFlowObservation.Direction.DEPARTURE, second.minusMillis(1)));

        assertTrue(anomalyEngine.evaluate(new AnomalyEvaluationTickEvent(
                AnomalyEvaluationTickEvent.Cadence.FAST, second), AnomalyProcessingMode.HISTORICAL_BUILD).stream()
                .noneMatch(finding -> locationId.equals(finding.componentId())
                        && finding.detector() == AnomalyDetectorType.OCCUPANCY_JAM));
    }

    @Test
    void transitDetectionRequiresThirtySamplesAndBothScores() {
        String suffix = UUID.randomUUID().toString();
        Instant boundary = nextTick(AnomalyEvaluationTickEvent.Cadence.MINUTE);
        Instant observedAt = boundary.minusMillis(1);
        String qualifiedConveyor = "qualified-" + suffix;
        String underSampledConveyor = "undersampled-" + suffix;
        putTransitBaseline(qualifiedConveyor, 30, observedAt);
        putTransitBaseline(underSampledConveyor, 29, observedAt);
        anomalyRepository.appendTransit(new TransitObservation("transit-qualified-" + suffix, "item-a",
                qualifiedConveyor, "source", "target", 2_000, observedAt));
        anomalyRepository.appendTransit(new TransitObservation("transit-undersampled-" + suffix, "item-b",
                underSampledConveyor, "source", "target", 2_000, observedAt));

        var findings = anomalyEngine.evaluate(new AnomalyEvaluationTickEvent(
                AnomalyEvaluationTickEvent.Cadence.MINUTE, boundary), AnomalyProcessingMode.HISTORICAL_BUILD);

        assertEquals(1, findings.stream()
                .filter(finding -> finding.detector() == AnomalyDetectorType.TRANSIT_TIME_SLOW)
                .filter(finding -> qualifiedConveyor.equals(finding.componentId()))
                .filter(finding -> finding.zScore() >= 3.0 && finding.modifiedZScore() >= 3.5)
                .count());
        assertTrue(findings.stream().noneMatch(finding -> underSampledConveyor.equals(finding.componentId())));
    }

    private Instant nextTick(AnomalyEvaluationTickEvent.Cadence cadence) {
        Instant previous = anomalyRepository.getLastBoundary(cadence);
        return anomalyEngine.nextBoundary(previous != null ? previous : Instant.now(), cadence);
    }

    private void putTransitBaseline(String conveyorId, long samples, Instant observationTimestamp) {
        String key = "transit:" + conveyorId + ":source:target";
        anomalyRepository.putBaseline(key, new DetectorBaseline("baseline-" + conveyorId, "live", "v1",
                AnomalyDetectorType.TRANSIT_TIME_SLOW, conveyorId, key,
                observationTimestamp.minusSeconds(7 * 24 * 3600L), observationTimestamp.minusMillis(1), samples,
                1_000.0, 100.0, 1_000.0, 50.0, 800.0, 1_200.0,
                observationTimestamp.minusSeconds(60), Instant.EPOCH));
    }
}
