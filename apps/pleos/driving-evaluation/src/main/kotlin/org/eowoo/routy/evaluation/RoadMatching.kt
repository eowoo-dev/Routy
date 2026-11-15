package org.eowoo.routy.evaluation

import kotlin.math.*

internal fun deltaHeading(from: Double, to: Double): Double = ((to - from + 540.0) % 360.0) - 180.0
internal fun bearing(a: GeoPoint, b: GeoPoint): Double = (Math.toDegrees(atan2(
    (b.longitude-a.longitude)*cos(Math.toRadians((a.latitude+b.latitude)/2)), b.latitude-a.latitude)) + 360) % 360
internal fun distance(a: GeoPoint, b: GeoPoint): Double = hypot((b.longitude-a.longitude)*111_195*cos(Math.toRadians((a.latitude+b.latitude)/2)), (b.latitude-a.latitude)*111_195)

class RoadMatcher(private val config: PracticeConfig = PracticeConfig()) {
    fun match(session: DrivingSession, sample: Observation<PositionFix>): RoadMatch {
        val at = sample.atMs
        val fix = sample.value
        val vehicleHeading = fix?.headingDeg
        if (!sample.available || fix == null || !fix.point.valid || vehicleHeading == null || !vehicleHeading.isFinite() || vehicleHeading !in 0.0..360.0)
            return RoadMatch(at,MatchStatus.INVALID_POSITION)
        val network = session.roadNetwork ?: return RoadMatch(at,MatchStatus.NO_MAP)
        if (network.revision.isBlank() || network.segments.isEmpty() || network.segments.map { it.id }.distinct().size != network.segments.size ||
            network.segments.any { it.id.isBlank() || it.fromNode.isBlank() || it.toNode.isBlank() || it.geometry.size < 2 || it.geometry.any { point -> !point.valid } } ||
            (session.origin != DataOrigin.SYNTHETIC && network.provenance == RoadProvenance.SYNTHETIC))
            return RoadMatch(at,MatchStatus.INVALID_MAP)
        val accuracy = fix.accuracyM ?: return RoadMatch(at,MatchStatus.ACCURACY_UNKNOWN)
        if (!accuracy.isFinite() || accuracy <= 0 || accuracy > config.maxAccuracyM) return RoadMatch(at,MatchStatus.ACCURACY_POOR)
        data class Candidate(val road: RoadSegment, val distance: Double, val heading: Double, val reversed: Boolean)
        val candidates = network.segments.filter { it.validUntilMs > at }.mapNotNull { road ->
            road.geometry.zipWithNext().mapNotNull { (a,b) ->
                val ax = (a.longitude-fix.point.longitude)*111_195*cos(Math.toRadians(fix.point.latitude))
                val ay = (a.latitude-fix.point.latitude)*111_195
                val bx = (b.longitude-fix.point.longitude)*111_195*cos(Math.toRadians(fix.point.latitude))
                val by = (b.latitude-fix.point.latitude)*111_195
                val length2 = (bx-ax).pow(2)+(by-ay).pow(2)
                if (length2 < 0.01) return@mapNotNull null
                val t = (-(ax*(bx-ax)+ay*(by-ay))/length2).coerceIn(0.0,1.0)
                val d = hypot(ax+t*(bx-ax),ay+t*(by-ay))
                val heading = bearing(a,b)
                val reverse = road.bidirectional && abs(deltaHeading(vehicleHeading, (heading+180)%360)) < abs(deltaHeading(vehicleHeading,heading))
                val wanted = if (reverse) (heading+180)%360 else heading
                if (abs(deltaHeading(vehicleHeading,wanted)) > 60) null else Candidate(road,d,wanted,reverse)
            }.minByOrNull { it.distance }
        }.filter { it.distance <= config.matchDistanceM }.sortedBy { it.distance }
        val best = candidates.firstOrNull() ?: return RoadMatch(at,
            if (network.segments.all { it.validUntilMs <= at }) MatchStatus.EXPIRED else MatchStatus.OUTSIDE_MAP)
        if (candidates.size > 1 && candidates[1].distance-best.distance <= max(config.ambiguityMarginM,accuracy*2))
            return RoadMatch(at,MatchStatus.AMBIGUOUS)
        return RoadMatch(at,MatchStatus.MATCHED,best.road.id,best.distance,best.heading,
            if (best.reversed) best.road.toNode else best.road.fromNode,
            if (best.reversed) best.road.fromNode else best.road.toNode,
            best.road.speedLimitMps?.takeIf { it.isFinite() && it > 0 })
    }
}
