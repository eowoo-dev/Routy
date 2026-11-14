package com.noonmaru.routy.evaluation

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

internal fun evaluateSteering(
    session: DrivingSession,
    config: EvaluationConfig,
    events: MutableList<EvaluationEvent>,
): Pair<SteeringMetrics, Assessment> {
    val reasons = linkedSetOf<ExclusionReason>()
    val timeline = Timeline(session.steeringAngles, session, config.steeringMaxIntervalMs, session.steeringGaps) { angle, _ ->
        if (!angle.isFinite() || abs(angle) > config.maxSteeringAngleDeg) ExclusionReason.INVALID_VALUE else null
    }
    var eligible = 0L
    var integral = 0.0
    var peak: Double? = null
    var rapidStart: Long? = null
    var rapidEnd = 0L
    var rapidPeak = 0.0
    var direction = 0
    var excursion = 0.0
    var candidateDirection = 0
    var candidateExcursion = 0.0
    var lastMovementEnd: Long? = null
    var reversals = 0
    val cluster = mutableListOf<Long>()
    var qualifiedCluster = false
    fun flushRapid() {
        rapidStart?.let { start -> if (rapidEnd - start >= config.sustainedSteeringMs) {
            events += EvaluationEvent(EventKind.RAPID_STEERING, start, rapidEnd,
                mapOf("peakRateDegPerSec" to rapidPeak.toString(), "timeBasis" to session.steeringTimeBasis.name, "cause" to "unknown"))
        } }
        rapidStart = null
        rapidPeak = 0.0
    }
    fun flushCluster() {
        if (qualifiedCluster) events += EvaluationEvent(EventKind.REPEATED_STEERING_REVERSALS, cluster.first(), cluster.last(),
            mapOf("reversals" to cluster.size.toString(), "minExcursionDeg" to config.reversalExcursionDeg.toString(),
                "windowMs" to config.reversalWindowMs.toString(), "timeBasis" to session.steeringTimeBasis.name, "cause" to "unknown"))
        cluster.clear()
        qualifiedCluster = false
    }
    fun reset() {
        flushRapid(); flushCluster()
        direction = 0; excursion = 0.0; candidateDirection = 0; candidateExcursion = 0.0; lastMovementEnd = null
    }
    if (session.steeringTimeBasis == SteeringTimeBasis.UNKNOWN) reasons += ExclusionReason.STEERING_TIME_BASIS_UNKNOWN
    timeline.issue?.let(reasons::add)
    if (session.steeringAngles.size < 2) reasons += ExclusionReason.INSUFFICIENT_STEERING_HISTORY
    if (timeline.issue == null && session.steeringTimeBasis != SteeringTimeBasis.UNKNOWN) {
        for ((left, right) in session.steeringAngles.zipWithNext()) {
            val a = timeline.read(left.atMs)
            val b = timeline.read(right.atMs)
            val dt = right.atMs - left.atMs
            var reason = a.reason ?: b.reason
            if (session.steeringGaps.any { it.startMs < right.atMs && it.endMs > left.atMs }) reason = ExclusionReason.UNAVAILABLE
            if (reason == null && dt !in config.derivativeMinIntervalMs..config.steeringMaxIntervalMs) reason = ExclusionReason.SAMPLE_INTERVAL_UNSUITABLE
            val delta = if (reason == null) b.value!! - a.value!! else 0.0
            val rate = if (reason == null) delta * 1_000.0 / dt else 0.0
            if (reason == null && abs(rate) > config.maxSteeringRateDegPerSec) reason = ExclusionReason.IMPLAUSIBLE_STEERING_CHANGE
            if (reason != null) { reasons += reason; reset(); continue }
            eligible += dt
            integral += abs(rate) * dt
            peak = max(peak ?: 0.0, abs(rate))
            if (abs(rate) >= config.rapidSteeringRateDegPerSec) {
                if (rapidStart == null) rapidStart = left.atMs
                rapidEnd = right.atMs
                rapidPeak = max(rapidPeak, abs(rate))
            } else flushRapid()

            if (lastMovementEnd != null && right.atMs - lastMovementEnd!! > config.reversalWindowMs) {
                flushCluster(); direction = 0; excursion = 0.0; candidateDirection = 0; candidateExcursion = 0.0
            }
            val movingDirection = delta.sign.toInt()
            if (movingDirection == 0) continue
            lastMovementEnd = right.atMs
            if (direction == 0) {
                if (candidateDirection != movingDirection) { candidateDirection = movingDirection; candidateExcursion = 0.0 }
                candidateExcursion += abs(delta)
                if (candidateExcursion >= config.reversalExcursionDeg) {
                    direction = movingDirection; excursion = candidateExcursion; candidateExcursion = 0.0; candidateDirection = 0
                }
            } else if (movingDirection == direction) {
                excursion += abs(delta)
                candidateDirection = 0; candidateExcursion = 0.0
            } else {
                if (candidateDirection != movingDirection) { candidateDirection = movingDirection; candidateExcursion = 0.0 }
                candidateExcursion += abs(delta)
                if (candidateExcursion >= config.reversalExcursionDeg) {
                    reversals++
                    if (cluster.isNotEmpty() && right.atMs - cluster.last() > config.reversalWindowMs) flushCluster()
                    cluster += right.atMs
                    if (cluster.size >= config.repeatedReversalCount &&
                        cluster.last() - cluster[cluster.size - config.repeatedReversalCount] <= config.reversalWindowMs) qualifiedCluster = true
                    direction = movingDirection; excursion = candidateExcursion; candidateDirection = 0; candidateExcursion = 0.0
                }
            }
        }
    }
    reset()
    if (eligible < session.durationMs && reasons.isEmpty()) reasons += ExclusionReason.INSUFFICIENT_STEERING_HISTORY
    val status = when { eligible == 0L -> AssessmentStatus.NOT_ASSESSED; eligible < session.durationMs -> AssessmentStatus.PARTIAL; else -> AssessmentStatus.ASSESSED }
    return SteeringMetrics(eligible, if (eligible > 0) integral / eligible else null, peak, reversals, session.steeringTimeBasis) to
        Assessment(Category.STEERING_CONTROL, status, eligible, session.durationMs - eligible, reasons = reasons)
}
