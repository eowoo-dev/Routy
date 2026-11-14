package com.noonmaru.routy.evaluation

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Deterministic batch/replay engine. One invocation contains exactly one trip window. */
class DrivingEvaluationEngine(private val config: EvaluationConfig = EvaluationConfig(), private val practiceConfig: PracticeConfig = PracticeConfig()) {
    fun evaluate(input: DrivingSession): EvaluationReport {
        val normalized = PracticeInputs.normalize(input)
        val practice = PracticeAnalyzer(practiceConfig).analyze(normalized)
        val session = normalized.copy(
            roads=if (normalized.roads.isEmpty()) PracticeInputs.roads(normalized,practice) else normalized.roads,
            turns=if (normalized.turns.isEmpty()) practice.confirmedTurns else normalized.turns,
            turnDetectionComplete=normalized.turnDetectionComplete || (normalized.turns.isEmpty() && practice.turnDetectionComplete))
        val speeds = Timeline(session.speeds, session, config.speedMaxAgeMs, session.speedGaps) { value, _ ->
            when {
                !value.isFinite() || value > config.maxPlausibleSpeedMps -> ExclusionReason.INVALID_VALUE
                value < 0 -> ExclusionReason.REVERSE_UNSUPPORTED
                else -> null
            }
        }
        val roads = Timeline(session.roads, session, config.roadMaxAgeMs) { road, at ->
            when {
                road.matchedRoadId.isBlank() || road.speedLimitRoadId != road.matchedRoadId -> ExclusionReason.ROAD_MISMATCH
                !road.speedLimitMps.isFinite() || road.speedLimitMps <= 0 -> ExclusionReason.INVALID_VALUE
                road.validUntilMs <= at -> ExclusionReason.STALE
                else -> null
            }
        }
        val signals = Timeline(session.signals, session, config.signalMaxAgeMs) { _, _ -> null }
        val events = mutableListOf<EvaluationEvent>()
        val (speedMetrics, speedAssessment) = evaluateSpeed(session, speeds, roads, events)
        val (motionMetrics, motionAssessment) = evaluateMotion(session, speeds, events)
        val (turnMetrics, turnAssessment) = evaluateTurns(session, signals, events)
        val (steeringMetrics, steeringAssessment) = evaluateSteering(session, config, events)
        val deferred = listOf(Category.LANE_KEEPING, Category.TRAFFIC_SIGNAL_RESPONSE, Category.FOLLOWING_DISTANCE)
            .map { Assessment(it, AssessmentStatus.NOT_ASSESSED, excludedMs = session.durationMs,
                reasons = setOf(ExclusionReason.ENVIRONMENT_DATA_REQUIRED, ExclusionReason.RULE_NOT_IMPLEMENTED)) }
        val allAssessments = listOf(speedAssessment,motionAssessment,turnAssessment,steeringAssessment) + deferred
        val orderedEvents = events.sortedWith(compareBy<EvaluationEvent> { it.startMs }.thenBy { it.kind.ordinal }.thenBy { it.endMs })
        val contexts = fuseContext(session,orderedEvents)
        val signalBoundaries = (listOf(session.startMs,session.endMs)+signals.boundaries()).filter { it in session.startMs..session.endMs }.distinct().sorted()
        val signalEligible = if (session.signalSemantics != SignalSemantics.SELECTION_STATE) 0L else
            signalBoundaries.zipWithNext().sumOf { (a,b) -> if (signals.read(a).value != null) b-a else 0L }
        val signalReasons = signalBoundaries.dropLast(1).mapNotNull { signals.read(it).reason?.name }.toSet() +
            (if (session.signalSemantics != SignalSemantics.SELECTION_STATE) setOf("UNKNOWN_SIGNAL_SEMANTICS") else emptySet()) +
            (if (!session.hazardStateKnown) setOf("HAZARD_CONTEXT_UNKNOWN") else emptySet())
        val quality = practice.quality + listOf(
            ChannelQuality("accelerationInput",motionAssessment.eligibleMs,session.durationMs,session.speeds.size,motionAssessment.reasons.map { it.name }.toSet()),
            ChannelQuality("steeringInput",steeringAssessment.eligibleMs,session.durationMs,session.steeringAngles.size,steeringAssessment.reasons.map { it.name }.toSet()),
            ChannelQuality("turnSignalSelection",signalEligible,session.durationMs,session.signals.size,signalReasons))
        val enrichedPractice = practice.copy(quality=quality)
        return EvaluationReport("0.4.0", config, session.origin, session.startMs, session.endMs,
            listOf(speedAssessment, motionAssessment, turnAssessment, steeringAssessment) + deferred,
            speedMetrics, motionMetrics, turnMetrics,
            orderedEvents, steeringMetrics,
            TelemetrySummary(session.telemetry.map { it.signal }.distinct().size, session.telemetry.size,
                session.telemetry.count { it.kind == ReadingKind.UPDATE }, session.telemetryTruncated, session.sensorAvailability),
            contexts,enrichedPractice,LearningFeedbackBuilder(practiceConfig,config.signalLeadMs).build(session,orderedEvents,contexts,enrichedPractice,allAssessments,turnMetrics),practiceConfig)
    }

    private fun evaluateSpeed(
        session: DrivingSession,
        speeds: Timeline<Double>,
        roads: Timeline<RoadContext>,
        events: MutableList<EvaluationEvent>,
    ): Pair<SpeedMetrics, Assessment> {
        val reasons = linkedSetOf<ExclusionReason>()
        var eligible = 0L
        var excessive = 0L
        var distance = 0.0
        var excessiveDistance = 0.0
        var peak: Double? = null
        var episodeStart: Long? = null
        var episodePeak = 0.0
        var episodeRoad: Pair<String, Double>? = null
        fun flush(end: Long) {
            val start = episodeStart
            if (start != null && end - start >= config.sustainedSpeedMs) {
                events += EvaluationEvent(EventKind.SUSTAINED_SPEED_EXCESS, start, end,
                    mapOf("roadId" to episodeRoad!!.first, "speedLimitMps" to episodeRoad!!.second.toString(),
                        "peakExcessMps" to episodePeak.toString(), "thresholdMps" to config.speedExcessMps.toString()))
            }
            episodeStart = null
            episodeRoad = null
            episodePeak = 0.0
        }
        val boundaries = (listOf(session.startMs, session.endMs) + speeds.boundaries() + roads.boundaries()
            + session.roads.mapNotNull { it.value?.validUntilMs }).filter { it in session.startMs..session.endMs }.distinct().sorted()
        for ((start, end) in boundaries.zipWithNext()) {
            val speed = speeds.read(start)
            val road = roads.read(start)
            if (speed.value == null || road.value == null) {
                speed.reason?.let(reasons::add)
                road.reason?.let(reasons::add)
                flush(start)
                continue
            }
            val dt = end - start
            val excess = speed.value - road.value.speedLimitMps
            eligible += dt
            distance += speed.value * dt / 1_000.0
            peak = max(peak ?: 0.0, max(0.0, excess))
            if (excess > config.speedExcessMps) {
                excessive += dt
                excessiveDistance += speed.value * dt / 1_000.0
            }
            val key = road.value.matchedRoadId to road.value.speedLimitMps
            if (episodeRoad != null && episodeRoad != key) flush(start)
            if (episodeStart != null && excess <= config.speedExcessMps - config.speedHysteresisMps) flush(start)
            if (episodeStart == null && excess > config.speedExcessMps) {
                episodeStart = start
                episodeRoad = key
            }
            if (episodeStart != null) episodePeak = max(episodePeak, excess)
        }
        flush(session.endMs)
        return SpeedMetrics(eligible, excessive, distance, excessiveDistance, peak) to
            durationAssessment(Category.SPEED_MANAGEMENT, eligible, session.durationMs, reasons)
    }

    private fun evaluateMotion(
        session: DrivingSession,
        speeds: Timeline<Double>,
        events: MutableList<EvaluationEvent>,
    ): Pair<MotionMetrics, Assessment> {
        val reasons = linkedSetOf<ExclusionReason>()
        var eligible = 0L
        var absoluteIntegral = 0.0
        var peakAcceleration: Double? = null
        var peakDeceleration: Double? = null
        var smoothed: Double? = null
        var episodeKind: EventKind? = null
        var episodeStart = 0L
        var episodeEnd = 0L
        var episodePeak = 0.0
        fun flush() {
            val kind = episodeKind
            if (kind != null && episodeEnd - episodeStart >= config.sustainedAccelerationMs) {
                events += EvaluationEvent(kind, episodeStart, episodeEnd,
                    mapOf("peakSmoothedAccelerationMagnitudeMps2" to episodePeak.toString(),
                        "filter" to "time-aware acceleration EMA", "cause" to "unknown"))
            }
            episodeKind = null
            episodePeak = 0.0
        }
        if (speeds.issue != null) reasons += speeds.issue
        if (session.speeds.size < 2) reasons += ExclusionReason.INSUFFICIENT_SPEED_HISTORY
        if (speeds.issue == null) for ((left, right) in session.speeds.zipWithNext()) {
            val first = speeds.read(left.atMs)
            val second = speeds.read(right.atMs)
            val dt = right.atMs - left.atMs
            var reason = first.reason ?: second.reason
            if (session.speedGaps.any { it.startMs < right.atMs && it.endMs > left.atMs }) reason = ExclusionReason.UNAVAILABLE
            if (reason == null && dt !in config.derivativeMinIntervalMs..config.derivativeMaxIntervalMs) {
                reason = ExclusionReason.SAMPLE_INTERVAL_UNSUITABLE
            }
            val raw = if (reason == null) (second.value!! - first.value!!) * 1_000.0 / dt else null
            if (raw != null && abs(raw) > config.maxPlausibleAccelerationMps2) reason = ExclusionReason.IMPLAUSIBLE_SPEED_CHANGE
            if (reason != null) {
                reasons += reason
                flush()
                smoothed = null
                continue
            }
            val alpha = if (config.accelerationSmoothingMs == 0L) 1.0 else 1.0 - exp(-dt.toDouble() / config.accelerationSmoothingMs)
            val acceleration = smoothed?.let { it + alpha * (raw!! - it) } ?: raw!!
            smoothed = acceleration
            eligible += dt
            absoluteIntegral += abs(acceleration) * dt
            peakAcceleration = max(peakAcceleration ?: 0.0, max(0.0, acceleration))
            peakDeceleration = max(peakDeceleration ?: 0.0, max(0.0, -acceleration))
            val kind = when {
                acceleration >= config.accelerationReviewMps2 -> EventKind.RAPID_ACCELERATION
                acceleration <= -config.decelerationReviewMps2 -> EventKind.RAPID_DECELERATION
                else -> null
            }
            if (kind != episodeKind) {
                flush()
                if (kind != null) { episodeKind = kind; episodeStart = left.atMs }
            }
            if (kind != null) {
                episodeEnd = right.atMs
                episodePeak = max(episodePeak, abs(acceleration))
            }
        }
        flush()
        if (eligible < session.durationMs && reasons.isEmpty()) reasons += ExclusionReason.INSUFFICIENT_SPEED_HISTORY
        return MotionMetrics(eligible, if (eligible > 0) absoluteIntegral / eligible else null,
            peakAcceleration, peakDeceleration) to durationAssessment(Category.ACCELERATION_CONTROL, eligible, session.durationMs, reasons)
    }

    private fun evaluateTurns(
        session: DrivingSession,
        signals: Timeline<SignalSelection>,
        events: MutableList<EvaluationEvent>,
    ): Pair<TurnMetrics, Assessment> {
        val reasons = linkedSetOf<ExclusionReason>()
        if (!session.turnDetectionComplete) reasons += ExclusionReason.TURN_DETECTION_INCOMPLETE
        var evaluated = 0
        var adequate = 0
        val duplicateIds = session.turns.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        for (turn in session.turns) {
            val invalid = when {
                turn.id.isBlank() || turn.id in duplicateIds -> ExclusionReason.DUPLICATE_MANEUVER
                turn.startMs < session.startMs || turn.endMs > session.endMs || turn.endMs <= turn.startMs -> ExclusionReason.INCOMPLETE_MANEUVER
                !turn.evidence.containsAll(setOf(TurnEvidence.MATCHED_ROAD_TRANSITION, TurnEvidence.OBSERVED_HEADING_CHANGE)) -> ExclusionReason.UNCONFIRMED_TURN
                turn.startMs - session.startMs < config.signalLeadMs -> ExclusionReason.INSUFFICIENT_LOOKBACK
                session.signalSemantics != SignalSemantics.SELECTION_STATE -> ExclusionReason.UNKNOWN_SIGNAL_SEMANTICS
                !session.hazardStateKnown -> ExclusionReason.HAZARD_CONTEXT_UNKNOWN
                else -> null
            }
            if (invalid != null) { reasons += invalid; continue }
            val start = turn.startMs - config.signalLeadMs
            val times = (listOf(start, turn.startMs) + signals.boundaries().filter { it in start..turn.startMs }).distinct().sorted()
            val reads = times.map(signals::read)
            val missing = reads.mapNotNull { it.reason }
            if (missing.isNotEmpty()) { reasons += missing; continue }
            val values = reads.map { it.value!! }
            if (SignalSelection.HAZARD in values) { reasons += ExclusionReason.HAZARD_CONTEXT_UNKNOWN; continue }
            evaluated++
            val wanted = if (turn.direction == TurnDirection.LEFT) SignalSelection.LEFT else SignalSelection.RIGHT
            val atTurn = values.last()
            val kind = when {
                values.all { it == wanted } -> { adequate++; null }
                atTurn == wanted -> EventKind.LATE_TURN_SIGNAL
                atTurn != SignalSelection.OFF -> EventKind.OPPOSITE_TURN_SIGNAL
                wanted in values -> EventKind.INTERRUPTED_TURN_SIGNAL
                else -> EventKind.MISSING_TURN_SIGNAL
            }
            if (kind != null) events += EvaluationEvent(kind, turn.startMs, turn.endMs,
                mapOf("turnId" to turn.id, "direction" to turn.direction.name,
                    "requiredContinuousLeadMs" to config.signalLeadMs.toString(),
                    "turnEvidence" to turn.evidence.sortedBy { it.ordinal }.joinToString(",") { it.name }))
        }
        val status = when {
            session.turns.isEmpty() && !session.turnDetectionComplete -> AssessmentStatus.NOT_ASSESSED
            session.turns.isEmpty() -> AssessmentStatus.NO_OPPORTUNITY
            evaluated == 0 -> AssessmentStatus.NOT_ASSESSED
            evaluated < session.turns.size || !session.turnDetectionComplete -> AssessmentStatus.PARTIAL
            else -> AssessmentStatus.ASSESSED
        }
        return TurnMetrics(session.turns.size, evaluated, adequate) to Assessment(Category.TURN_SIGNAL_USE, status,
            opportunities = session.turns.size, evaluatedOpportunities = evaluated, reasons = reasons)
    }

    private fun durationAssessment(category: Category, eligible: Long, total: Long, reasons: Set<ExclusionReason>): Assessment =
        Assessment(category, when { eligible == 0L -> AssessmentStatus.NOT_ASSESSED; eligible < total -> AssessmentStatus.PARTIAL; else -> AssessmentStatus.ASSESSED },
            eligibleMs = eligible, excludedMs = total - eligible, reasons = reasons)
}

internal data class Read<T>(val value: T? = null, val reason: ExclusionReason? = null)

/** No sorting or implicit interpolation: a broken clock/order invalidates that input stream. */
internal class Timeline<T>(
    private val samples: List<Observation<T>>,
    private val session: DrivingSession,
    private val maxAgeMs: Long,
    private val gaps: List<TimeRange> = emptyList(),
    private val validate: (T, Long) -> ExclusionReason?,
) {
    val issue: ExclusionReason? = when {
        samples.any { it.atMs !in session.startMs..session.endMs } -> ExclusionReason.OUTSIDE_SESSION
        samples.zipWithNext().any { (a, b) -> b.atMs <= a.atMs } -> ExclusionReason.INVALID_TIMELINE
        else -> null
    }
    fun boundaries(): List<Long> = if (issue != null) emptyList() else samples.flatMap {
        listOf(it.atMs, it.atMs + min(maxAgeMs, session.endMs - it.atMs))
    } + gaps.flatMap { listOf(it.startMs, it.endMs) }

    fun read(at: Long): Read<T> {
        issue?.let { return Read(reason = it) }
        if (gaps.any { at >= it.startMs && at < it.endMs }) return Read(reason = ExclusionReason.UNAVAILABLE)
        var low = 0
        var high = samples.lastIndex
        var found = -1
        while (low <= high) {
            val middle = (low + high).ushr(1)
            if (samples[middle].atMs <= at) { found = middle; low = middle + 1 } else high = middle - 1
        }
        if (found < 0) return Read(reason = ExclusionReason.NO_DATA)
        val sample = samples[found]
        // Recovery requires a NEW provider observation; an old value cannot revive after a gap.
        if (gaps.any { at >= it.startMs && sample.atMs < it.endMs }) return Read(reason = ExclusionReason.UNAVAILABLE)
        val value = sample.value
        if (!sample.available || value == null) return Read(reason = ExclusionReason.UNAVAILABLE)
        if (at - sample.atMs >= maxAgeMs) return Read(reason = ExclusionReason.STALE)
        validate(value, at)?.let { return Read(reason = it) }
        return Read(value)
    }
}
