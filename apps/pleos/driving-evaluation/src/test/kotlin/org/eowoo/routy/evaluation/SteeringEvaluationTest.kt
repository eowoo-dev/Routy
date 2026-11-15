package org.eowoo.routy.evaluation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SteeringEvaluationTest {
    private fun evaluate(angles: List<Observation<Double>>, end: Long = 2_000, gaps: List<TimeRange> = emptyList(),
        basis: SteeringTimeBasis = SteeringTimeBasis.PROVIDER): EvaluationReport = DrivingEvaluationEngine().evaluate(
        DrivingSession(0, end, DataOrigin.SYNTHETIC, steeringAngles = angles, steeringGaps = gaps, steeringTimeBasis = basis))
    private fun angleSeries(end: Long = 2_000, f: (Long) -> Double) = (0L..end step 100).map { Observation(it, f(it)) }

    @Test fun noInputIsUnassessedNotZero() {
        val r = evaluate(emptyList())
        assertNull(r.steering.meanAbsoluteRateDegPerSec)
        assertEquals(AssessmentStatus.NOT_ASSESSED, r.assessments.single { it.category == Category.STEERING_CONTROL }.status)
    }
    @Test fun steadyWheelHasZeroRateWithoutClaimingLaneKeeping() {
        val r = evaluate(angleSeries { 45.0 })
        assertEquals(2_000L, r.steering.eligibleMs)
        assertEquals(0.0, r.steering.meanAbsoluteRateDegPerSec)
        assertTrue(r.events.isEmpty())
        assertEquals(AssessmentStatus.NOT_ASSESSED, r.assessments.single { it.category == Category.LANE_KEEPING }.status)
    }
    @Test fun knownRateUsesDegreesPerSecondAndReviewOnly() {
        for (direction in listOf(-1, 1)) {
            val r = evaluate(angleSeries { direction * it * 0.2 })
            assertEquals(200.0, r.steering.meanAbsoluteRateDegPerSec!!, 1e-6)
            val event = r.events.single { it.kind == EventKind.RAPID_STEERING }
            assertEquals(0L, event.startMs)
            assertEquals(2_000L, event.endMs)
            assertTrue(event.reviewOnly)
        }
    }
    @Test fun briefRapidMovementDoesNotBecomeSustainedEvent() {
        val r = evaluate(angleSeries { if (it <= 200) it * 0.2 else 40.0 })
        assertTrue(r.events.none { it.kind == EventKind.RAPID_STEERING })
    }
    @Test fun thresholdMinimumDurationIsInclusive() {
        val r = evaluate(angleSeries(300) { it * 0.18 }, 300)
        assertEquals(1, r.events.count { it.kind == EventKind.RAPID_STEERING })
    }
    @Test fun significantDirectionReversalsAreCountedWithoutZeroCrossing() {
        val r = evaluate(angleSeries { 100.0 + when ((it / 100) % 4) { 0L -> 0.0; 1L -> 15.0; 2L -> 30.0; else -> 15.0 } })
        assertTrue(r.steering.significantReversals >= 3)
        assertTrue(r.events.any { it.kind == EventKind.REPEATED_STEERING_REVERSALS })
    }
    @Test fun quantizationNoiseDoesNotCreateCorrectionEvents() {
        val r = evaluate(angleSeries { if (it % 200 == 0L) 1.0 else -1.0 })
        assertEquals(0, r.steering.significantReversals)
        assertTrue(r.events.isEmpty())
    }
    @Test fun missingSampleResetsDirectionAndEventContinuity() {
        val r = evaluate(listOf(Observation(0, 0.0), Observation(100, 20.0), Observation<Double>(200, null),
            Observation(300, 40.0), Observation(400, 60.0)), 400)
        assertTrue(r.events.isEmpty())
        assertEquals(200L, r.steering.eligibleMs)
    }
    @Test fun explicitGapBlocksDifferentiationBetweenAvailableEndpoints() {
        val r = evaluate(listOf(Observation(0, 0.0), Observation(500, 100.0)), 500, listOf(TimeRange(100, 200)))
        assertEquals(0L, r.steering.eligibleMs)
        assertTrue(r.events.isEmpty())
    }
    @Test fun slowUpdatesDoNotInventSmoothnessOrZeroRates() {
        val r = evaluate(listOf(Observation(0, 0.0), Observation(2_000, 100.0)))
        assertNull(r.steering.meanAbsoluteRateDegPerSec)
        assertTrue(r.events.isEmpty())
    }
    @Test fun duplicatesAndOutOfOrderAreRejected() {
        for (angles in listOf(listOf(Observation(0, 0.0), Observation(0, 20.0)),
            listOf(Observation(100, 0.0), Observation(0, 20.0)))) {
            val r = evaluate(angles)
            assertEquals(0L, r.steering.eligibleMs)
            assertTrue(ExclusionReason.INVALID_TIMELINE in r.assessments.single { it.category == Category.STEERING_CONTROL }.reasons)
        }
    }
    @Test fun unknownTimeBasisIsNotSilentlyInterpretedAsSensorTime() {
        assertEquals(0L, evaluate(angleSeries { it * 0.2 }, basis = SteeringTimeBasis.UNKNOWN).steering.eligibleMs)
        val r = evaluate(angleSeries { it * 0.2 }, basis = SteeringTimeBasis.CALLBACK_RECEIPT)
        assertEquals(SteeringTimeBasis.CALLBACK_RECEIPT, r.steering.timeBasis)
        assertEquals("CALLBACK_RECEIPT", r.events.single().evidence["timeBasis"])
    }
    @Test fun invalidAnglesAndImpossibleChangesDoNotBecomeRapidSteering() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, 3_000.0, 500.0)) {
            val r = evaluate(listOf(Observation(0, 0.0), Observation(100, value)))
            assertNull(r.steering.meanAbsoluteRateDegPerSec)
            assertTrue(r.events.isEmpty())
        }
    }
    @Test fun replayHasNoCrossSessionDirectionMemory() {
        val input = ExampleDrive.session()
        val engine = DrivingEvaluationEngine()
        val first = engine.evaluate(input)
        engine.evaluate(DrivingSession(0, 2_000, DataOrigin.SYNTHETIC))
        assertEquals(first, engine.evaluate(input))
        assertTrue(first.events.any { it.kind == EventKind.RAPID_STEERING })
        assertTrue(first.events.any { it.kind == EventKind.REPEATED_STEERING_REVERSALS })
    }
}
