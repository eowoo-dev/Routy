package com.noonmaru.routy.evaluation

enum class Category { SPEED_MANAGEMENT, ACCELERATION_CONTROL, TURN_SIGNAL_USE, STEERING_CONTROL, LANE_KEEPING, TRAFFIC_SIGNAL_RESPONSE, FOLLOWING_DISTANCE }
enum class AssessmentStatus { ASSESSED, PARTIAL, NOT_ASSESSED, NO_OPPORTUNITY }
enum class ExclusionReason {
    WINDOW_BOUNDARY,
    NO_DATA, INVALID_TIMELINE, OUTSIDE_SESSION, UNAVAILABLE, STALE, INVALID_VALUE,
    REVERSE_UNSUPPORTED, ROAD_MISMATCH, INSUFFICIENT_SPEED_HISTORY, SAMPLE_INTERVAL_UNSUITABLE,
    IMPLAUSIBLE_SPEED_CHANGE, INCOMPLETE_MANEUVER, UNCONFIRMED_TURN, INSUFFICIENT_LOOKBACK,
    UNKNOWN_SIGNAL_SEMANTICS, HAZARD_CONTEXT_UNKNOWN, DUPLICATE_MANEUVER,
    TURN_DETECTION_INCOMPLETE,
    STEERING_TIME_BASIS_UNKNOWN, INSUFFICIENT_STEERING_HISTORY, IMPLAUSIBLE_STEERING_CHANGE,
    ENVIRONMENT_DATA_REQUIRED, RULE_NOT_IMPLEMENTED, JERK_NOT_CALIBRATED,
    ENV_NO_OBSERVATION, ENV_CLOCK_UNCERTAIN, ENV_SDK_MISSING, ENV_SDK_MISMATCH, ENV_SDK_GAP,
    ENV_INVALID_SAMPLE, ENV_OUT_OF_SCOPE, ENV_UNAVAILABLE, ENV_DUPLICATE, ENV_TIME_GAP, ENV_RUN_CHANGED,
    ENV_POSITION_JUMP, ENV_SIGNAL_UNKNOWN, ENV_INTENTIONAL_CHANGE,
}

data class Assessment(
    val category: Category,
    val status: AssessmentStatus,
    val eligibleMs: Long = 0,
    val excludedMs: Long = 0,
    val opportunities: Int = 0,
    val evaluatedOpportunities: Int = 0,
    val reasons: Set<ExclusionReason> = emptySet(),
)

enum class EventKind { SUSTAINED_SPEED_EXCESS, RAPID_ACCELERATION, RAPID_DECELERATION, MISSING_TURN_SIGNAL, LATE_TURN_SIGNAL, OPPOSITE_TURN_SIGNAL, INTERRUPTED_TURN_SIGNAL, RAPID_STEERING, REPEATED_STEERING_REVERSALS }

/** All events are observations to review, never an automatic finding of driver fault. */
data class EvaluationEvent(
    val kind: EventKind,
    val startMs: Long,
    val endMs: Long,
    val evidence: Map<String, String>,
    val reviewOnly: Boolean = true,
)

data class SpeedMetrics(
    val eligibleMs: Long,
    val excessiveMs: Long,
    val eligibleDistanceM: Double,
    val excessiveDistanceM: Double,
    val peakExcessMps: Double?,
) {
    val excessiveTimeRatio: Double? get() = if (eligibleMs > 0) excessiveMs.toDouble() / eligibleMs else null
}

data class MotionMetrics(
    val eligibleMs: Long,
    val meanAbsoluteAccelerationMps2: Double?,
    val peakAccelerationMps2: Double?,
    val peakDecelerationMps2: Double?,
    val jerkExclusion: ExclusionReason = ExclusionReason.JERK_NOT_CALIBRATED,
)

data class TurnMetrics(val opportunities: Int, val evaluated: Int, val adequatelySignalled: Int)

/** Steering wheel rotation, not road wheel angle, yaw, lane position or driver fault. */
data class SteeringMetrics(
    val eligibleMs: Long,
    val meanAbsoluteRateDegPerSec: Double?,
    val peakAbsoluteRateDegPerSec: Double?,
    val significantReversals: Int,
    val timeBasis: SteeringTimeBasis,
)

data class EvaluationReport(
    val engineVersion: String,
    val config: EvaluationConfig,
    val origin: DataOrigin,
    val startMs: Long,
    val endMs: Long,
    val assessments: List<Assessment>,
    val speed: SpeedMetrics,
    val motion: MotionMetrics,
    val turns: TurnMetrics,
    val events: List<EvaluationEvent>,
    val steering: SteeringMetrics,
    val telemetry: TelemetrySummary,
    val contextFindings: List<ContextFinding>,
    val practice: PracticeAnalysis,
    val feedback: List<LearningFeedback>,
    val practiceConfig: PracticeConfig = PracticeConfig(),
    val environment: EnvironmentResult? = null,
)
