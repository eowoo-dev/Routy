package com.noonmaru.routy.evaluation

import kotlin.math.*

internal fun rawState(session: DrivingSession, key: String, at: Long): SensorReading? {
    if (session.telemetryTruncated) return null
    val rows = session.telemetry.filter { it.signal == key && it.atMs in session.startMs..at }
    val latest = rows.maxOfOrNull { it.atMs } ?: return null
    if (at-latest >= 2_000) return null
    val values = rows.filter { it.atMs == latest }
    if (values.map { listOf(it.state,it.flag,it.number,it.available) }.distinct().size != 1) return null
    return values.first().takeIf { it.available }
}

class PracticeAnalyzer(private val config: PracticeConfig = PracticeConfig()) {
    fun analyze(session: DrivingSession): PracticeAnalysis {
        val positions = session.positions
        val timeline = Timeline(positions,session,config.positionMaxAgeMs,session.positionGaps) { value, _ ->
            if (!value.point.valid || value.headingDeg?.let { !it.isFinite() || it !in 0.0..360.0 } != false) ExclusionReason.INVALID_VALUE else null
        }
        val speeds = Timeline(session.speeds,session,1_000,session.speedGaps) { v,_ ->
            if (!v.isFinite() || abs(v) > 100) ExclusionReason.INVALID_VALUE else null
        }
        val clockKnown = session.positionTimeBasis != SteeringTimeBasis.UNKNOWN
        val matches = positions.map { if (timeline.issue != null || !clockKnown || timeline.read(it.atMs).value == null) RoadMatch(it.atMs,MatchStatus.INVALID_POSITION) else RoadMatcher(config).match(session,it) }.toMutableList()
        val reasons = linkedSetOf<String>()
        if (positions.isEmpty()) reasons += "NO_POSITIONS"
        if (positions.size == 1) reasons += "INSUFFICIENT_POSITION_HISTORY"
        if (positions.any { it.value?.headingDeg?.let { h -> h.isFinite() && h in 0.0..360.0 } != true }) reasons += "HEADING_UNAVAILABLE"
        if (positions.any { it.value?.accuracyM?.let { a -> a.isFinite() && a > 0 } != true }) reasons += "POSITION_ACCURACY_UNKNOWN"
        if (!clockKnown) reasons += "POSITION_TIME_BASIS_UNKNOWN"
        timeline.issue?.let { reasons += it.name }
        val valid = mutableSetOf<Int>()
        var eligible = 0L
        var mapEligible = 0L
        val deltas = mutableMapOf<Int,Double>()
        for (i in 1 until positions.size) {
            val a = positions[i-1]; val b = positions[i]; val dt = b.atMs-a.atMs
            val av = timeline.read(a.atMs).value; val bv = timeline.read(b.atMs).value
            val speed = speeds.read(a.atMs).value; val rightSpeed = speeds.read(b.atMs).value
            val issue = when {
                !clockKnown || timeline.issue != null -> "INVALID_POSITION_TIMELINE"
                av == null || bv == null -> "UNAVAILABLE_POSITION"
                session.positionGaps.any { it.startMs < b.atMs && it.endMs > a.atMs } -> "POSITION_GAP"
                dt !in 50..config.positionMaxAgeMs -> "POSITION_SAMPLE_INTERVAL"
                speed == null || rightSpeed == null -> "SPEED_UNAVAILABLE_FOR_POSITION"
                session.speedGaps.any { it.startMs < b.atMs && it.endMs > a.atMs } -> "SPEED_GAP"
                rawState(session,"AAOS.GEAR_SELECTION",b.atMs)?.state == "PARK" && abs(speed) > 0.5 -> "GEAR_SPEED_CONFLICT"
                else -> null
            }
            if (issue != null) { reasons += issue; continue }
            val left = av!!; val right = bv!!
            val d = distance(left.point,right.point)
            val expected = (abs(speed!!)+abs(rightSpeed!!))/2*dt/1000.0
            val heading = deltaHeading(left.headingDeg!!,right.headingDeg!!)
            if (d*1000/dt > 100 || abs(d-expected) > max(5.0,expected*0.8) ||
                (expected >= 1 && d < 0.25) || abs(heading)*1000/dt > 90 ||
                (expected < 0.25 && d < 0.25 && abs(heading) > 1)) {
                reasons += "POSITION_MOTION_INCONSISTENT"
                matches[i] = RoadMatch(b.atMs,MatchStatus.INVALID_POSITION)
                continue
            }
            valid += i; deltas[i] = heading; eligible += dt
            if (matches[i-1].status == MatchStatus.MATCHED && matches[i].status == MatchStatus.MATCHED) mapEligible += dt
        }
        val maneuvers = mutableListOf<DetectedManeuver>()
        var start: Int? = null
        var lastMoving = -1
        var settledAt = -1L
        fun finish(post: Int, incomplete: Boolean) {
            val first = start ?: return
            val last = lastMoving
            if (last <= first) { start = null; return }
            val slice = positions.subList(first,last+1)
            val changes = (first+1..last).map { deltas[it] ?: 0.0 }
            val cumulative = changes.runningFold(0.0) { sum,d -> sum+d }
            val net = cumulative.last()
            val excursion = cumulative.maxOf { abs(it) }
            val before = matches[first]; val after = matches[post]
            val fix = positions[first].value!!
            val endFix = positions[last].value!!
            val pathSpeeds = slice.mapNotNull { speeds.read(it.atMs).value }
            val reverse = pathSpeeds.any { it < 0 } || slice.any { rawState(session,"AAOS.GEAR_SELECTION",it.atMs)?.state == "REVERSE" }
            val roadChange = if (before.roadHeadingDeg != null && after.roadHeadingDeg != null) deltaHeading(before.roadHeadingDeg,after.roadHeadingDeg) else null
            val sameRoad = before.status == MatchStatus.MATCHED && after.status == MatchStatus.MATCHED && before.roadId == after.roadId
            val connected = before.status == MatchStatus.MATCHED && after.status == MatchStatus.MATCHED && before.roadId != after.roadId && before.exitNode == after.entryNode
            val lateral = distance(fix.point,endFix.point)*sin(Math.toRadians(deltaHeading(fix.headingDeg!!,bearing(fix.point,endFix.point))))
            val maxAccuracy = slice.mapNotNull { it.value?.accuracyM?.takeIf { a -> a.isFinite() && a > 0 } }.maxOrNull()
            val accurate = slice.all { it.value?.accuracyM?.let { a -> a.isFinite() && a in 0.01..config.maxAccuracyM } == true }
            val missing = linkedSetOf<String>()
            if (!accurate) missing += "POSITION_ACCURACY"
            if (before.status != MatchStatus.MATCHED || after.status != MatchStatus.MATCHED) missing += "UNIQUE_MATCHED_ROADS"
            val kind = when {
                incomplete -> ManeuverKind.INCOMPLETE
                reverse -> ManeuverKind.REVERSE_MANEUVER
                pathSpeeds.maxOfOrNull { abs(it) }?.let { it < config.minMovingSpeedMps } == true -> ManeuverKind.LOW_SPEED_MANEUVER
                sameRoad && roadChange != null && abs(roadChange) >= 15 && abs(net-roadChange) <= 20 -> ManeuverKind.CURVE
                connected && accurate && abs(net) in config.turnMinDeg..config.turnMaxDeg -> if (net < 0) ManeuverKind.LEFT_TURN else ManeuverKind.RIGHT_TURN
                sameRoad && accurate && roadChange != null && abs(roadChange) <= 3 && abs(net) <= config.laneReturnDeg && excursion >= config.laneExcursionDeg &&
                    changes.any { it > 0.5 } && changes.any { it < -0.5 } && abs(lateral) >= max(1.0,2*(maxAccuracy ?: 100.0)) ->
                    if (lateral < 0) ManeuverKind.LANE_CHANGE_LEFT_CANDIDATE else ManeuverKind.LANE_CHANGE_RIGHT_CANDIDATE
                abs(net) in config.turnMinDeg..config.turnMaxDeg -> {
                    missing += "CONNECTED_ROAD_TRANSITION"
                    if (net < 0) ManeuverKind.LEFT_TURN_CANDIDATE else ManeuverKind.RIGHT_TURN_CANDIDATE
                }
                else -> ManeuverKind.UNKNOWN
            }
            val supported = kind in setOf(ManeuverKind.LEFT_TURN,ManeuverKind.RIGHT_TURN,ManeuverKind.CURVE)
            val evidence = linkedMapOf("headingChangeDeg" to net.toString(),"maxHeadingExcursionDeg" to excursion.toString(),
                "lateralDisplacementM" to lateral.toString(),"positionSamples" to slice.size.toString(),
                "roadBefore" to (before.roadId ?: "unknown"),"roadAfter" to (after.roadId ?: "unknown"),
                "roadHeadingChangeDeg" to (roadChange?.toString() ?: "unknown"),"directionSource" to "position-heading; indicators excluded")
            maneuvers += DetectedManeuver("detected-${positions[first].atMs}-${positions[last].atMs}",kind,positions[first].atMs,positions[last].atMs,net,evidence,
                if (supported) EvidenceStrength.SUPPORTED else if (kind == ManeuverKind.INCOMPLETE || kind == ManeuverKind.UNKNOWN) EvidenceStrength.INSUFFICIENT else EvidenceStrength.LIMITED,missing)
            start = null
        }
        for (i in 1 until positions.size) {
            if (i !in valid) { finish(lastMoving.coerceAtLeast(0),true); continue }
            val dt = positions[i].atMs-positions[i-1].atMs
            if (abs(deltas.getValue(i))*1000/dt >= config.headingRateDegPerSec) {
                if (start == null) start = i-1
                lastMoving = i; settledAt = -1
            } else if (start != null) {
                if (settledAt < 0) settledAt = positions[i-1].atMs
                if (positions[i].atMs-settledAt >= config.settleMs) finish(i,false)
            }
        }
        if (start != null) finish(lastMoving,true)
        // Stable trajectory episodes require displacement-consistent samples and known road geometry.
        var straightStart: Int? = null
        fun endStraight(last: Int) {
            val first = straightStart ?: return
            if (last > first && positions[last].atMs-positions[first].atMs >= 1_000) maneuvers += DetectedManeuver(
                "straight-${positions[first].atMs}",ManeuverKind.STRAIGHT,positions[first].atMs,positions[last].atMs,0.0,
                mapOf("roadId" to matches[first].roadId.orEmpty(),"directionSource" to "displacement-consistent position-heading"),EvidenceStrength.SUPPORTED)
            straightStart = null
        }
        for (i in 1 until positions.size) {
            val overlaps = maneuvers.any { positions[i-1].atMs < it.endMs && positions[i].atMs > it.startMs }
            val straight = i in valid && !overlaps && abs(deltas.getValue(i)) <= 1 && matches[i].status == MatchStatus.MATCHED &&
                matches[i-1].roadId == matches[i].roadId && matches[i-1].status == MatchStatus.MATCHED &&
                speeds.read(positions[i].atMs).value?.let { it >= config.minMovingSpeedMps } == true
            if (straight) { if (straightStart == null) straightStart = i-1 } else endStraight(i-1)
        }
        endStraight(positions.lastIndex)
        val ordered = maneuvers.sortedWith(compareBy<DetectedManeuver> { it.startMs }.thenBy { it.kind.ordinal })
        val confirmed = ordered.filter { it.kind == ManeuverKind.LEFT_TURN || it.kind == ManeuverKind.RIGHT_TURN }.map {
            TurnManeuver(it.id,it.startMs,it.endMs,if (it.kind == ManeuverKind.LEFT_TURN) TurnDirection.LEFT else TurnDirection.RIGHT,
                setOf(TurnEvidence.MATCHED_ROAD_TRANSITION,TurnEvidence.OBSERVED_HEADING_CHANGE))
        }
        val unknown = ordered.any { it.strength != EvidenceStrength.SUPPORTED }
        val complete = eligible == session.durationMs && mapEligible == session.durationMs && !unknown && positions.isNotEmpty()
        return PracticeAnalysis(ordered,matches,listOf(
            ChannelQuality("trajectory",eligible,session.durationMs,positions.size,reasons),
            ChannelQuality("roadMatching",mapEligible,session.durationMs,matches.size,matches.filter { it.status != MatchStatus.MATCHED }.map { it.status.name }.toSet())),confirmed,complete)
    }
}
