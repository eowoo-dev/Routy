package org.eowoo.routy.evaluation

/** All times are milliseconds in ONE monotonic session clock, never wall-clock UTC. */
data class Observation<T>(
    val atMs: Long,
    val value: T?,
    val available: Boolean = true,
)

enum class DataOrigin { LIVE, RECORDED, SYNTHETIC }
enum class SteeringTimeBasis { PROVIDER, CALLBACK_RECEIPT, UNKNOWN }
enum class SignalSelection { OFF, LEFT, RIGHT, HAZARD }
enum class SignalSemantics { SELECTION_STATE, BLINK_LAMP, UNKNOWN }
enum class TurnDirection { LEFT, RIGHT }
enum class TurnEvidence { MATCHED_ROAD_TRANSITION, OBSERVED_HEADING_CHANGE, STEERING_PATTERN, NAVIGATION_INSTRUCTION }

/** An explicit loss of observations, recorded separately from provider-timestamped samples. */
data class TimeRange(val startMs: Long, val endMs: Long) {
    init { require(startMs >= 0 && endMs > startMs) }
}

/** Map adapter must invalidate this observation on a road transition or match loss. */
data class RoadContext(
    val matchedRoadId: String,
    val speedLimitRoadId: String,
    val speedLimitMps: Double,
    val validUntilMs: Long,
)

/** A completed observed maneuver, not a navigation instruction or a turn-signal trigger. */
data class TurnManeuver(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val direction: TurnDirection,
    val evidence: Set<TurnEvidence>,
)

data class DrivingSession(
    val startMs: Long,
    val endMs: Long,
    val origin: DataOrigin,
    val speeds: List<Observation<Double>> = emptyList(),
    val roads: List<Observation<RoadContext>> = emptyList(),
    val signals: List<Observation<SignalSelection>> = emptyList(),
    val signalSemantics: SignalSemantics = SignalSemantics.UNKNOWN,
    val turns: List<TurnManeuver> = emptyList(),
    val speedGaps: List<TimeRange> = emptyList(),
    /** True only when an independent maneuver detector covered the entire session. */
    val turnDetectionComplete: Boolean = false,
    val steeringAngles: List<Observation<Double>> = emptyList(),
    val steeringGaps: List<TimeRange> = emptyList(),
    val steeringTimeBasis: SteeringTimeBasis = SteeringTimeBasis.UNKNOWN,
    val telemetry: List<SensorReading> = emptyList(),
    val sensorAvailability: List<SensorAvailability> = emptyList(),
    val telemetryTruncated: Boolean = false,
    val positions: List<Observation<PositionFix>> = emptyList(),
    val positionGaps: List<TimeRange> = emptyList(),
    val positionTimeBasis: SteeringTimeBasis = SteeringTimeBasis.UNKNOWN,
    val roadNetwork: RoadNetwork? = null,
    /** SDK selection without observable hazards must not imply that hazards are OFF. */
    val hazardStateKnown: Boolean = true,
    val environment: List<AlignedEnvironmentFrame> = emptyList(),
    val environmentRuleVersion: String = "environment-0.3",
) {
    init {
        require(startMs >= 0 && endMs > startMs) { "A session needs a positive monotonic time range." }
        require(speedGaps.all { it.startMs >= startMs && it.endMs <= endMs })
        require(steeringGaps.all { it.startMs >= startMs && it.endMs <= endMs })
        require(positionGaps.all { it.startMs >= startMs && it.endMs <= endMs })
    }
    val durationMs: Long get() = endMs - startMs
}

/** Experimental learning thresholds; NOT calibrated safety or legal enforcement criteria. */
data class EvaluationConfig(
    val ruleVersion: String = "experimental-0.4",
    val speedMaxAgeMs: Long = 1_000,
    val roadMaxAgeMs: Long = 2_000,
    val signalMaxAgeMs: Long = 1_000,
    val speedExcessMps: Double = 5.0 / 3.6,
    val speedHysteresisMps: Double = 1.0 / 3.6,
    val sustainedSpeedMs: Long = 2_000,
    val derivativeMinIntervalMs: Long = 50,
    val derivativeMaxIntervalMs: Long = 500,
    val accelerationSmoothingMs: Long = 300,
    val accelerationReviewMps2: Double = 2.5,
    val decelerationReviewMps2: Double = 3.5,
    val sustainedAccelerationMs: Long = 500,
    val maxPlausibleSpeedMps: Double = 100.0,
    val maxPlausibleAccelerationMps2: Double = 12.0,
    val signalLeadMs: Long = 3_000,
    val steeringMaxIntervalMs: Long = 1_000,
    val maxSteeringAngleDeg: Double = 2_000.0,
    val maxSteeringRateDegPerSec: Double = 1_500.0,
    val rapidSteeringRateDegPerSec: Double = 180.0,
    val sustainedSteeringMs: Long = 300,
    val reversalExcursionDeg: Double = 10.0,
    val repeatedReversalCount: Int = 3,
    val reversalWindowMs: Long = 5_000,
) {
    init {
        require(ruleVersion.isNotBlank())
        require(listOf(speedMaxAgeMs, roadMaxAgeMs, signalMaxAgeMs, sustainedSpeedMs,
            derivativeMinIntervalMs, derivativeMaxIntervalMs, sustainedAccelerationMs, signalLeadMs).all { it > 0 })
        require(accelerationSmoothingMs >= 0 && derivativeMinIntervalMs <= derivativeMaxIntervalMs)
        require(derivativeMaxIntervalMs <= speedMaxAgeMs)
        require(listOf(speedExcessMps, speedHysteresisMps, accelerationReviewMps2,
            decelerationReviewMps2, maxPlausibleSpeedMps, maxPlausibleAccelerationMps2).all { it.isFinite() && it > 0 })
        require(speedHysteresisMps <= speedExcessMps)
        require(accelerationReviewMps2 < maxPlausibleAccelerationMps2 && decelerationReviewMps2 < maxPlausibleAccelerationMps2)
        require(steeringMaxIntervalMs >= derivativeMinIntervalMs && sustainedSteeringMs > 0 && reversalWindowMs > 0)
        require(repeatedReversalCount >= 2)
        require(listOf(maxSteeringAngleDeg, maxSteeringRateDegPerSec, rapidSteeringRateDegPerSec, reversalExcursionDeg).all { it.isFinite() && it > 0 })
        require(rapidSteeringRateDegPerSec <= maxSteeringRateDegPerSec)
    }
}
