package org.eowoo.routy.evaluation

data class GeoPoint(val latitude: Double, val longitude: Double) {
    val valid get() = latitude.isFinite() && longitude.isFinite() && latitude in -85.0..85.0 && longitude in -180.0..180.0
}
data class PositionFix(val point: GeoPoint, val headingDeg: Double?, val accuracyM: Double?, val source: String)
enum class RoadProvenance { SYNTHETIC, VERIFIED_MAP }
/** Directed geometry. Nodes encode topology; a geometric crossing alone is not a junction. */
data class RoadSegment(val id: String, val fromNode: String, val toNode: String, val geometry: List<GeoPoint>,
    val speedLimitMps: Double?, val validUntilMs: Long, val bidirectional: Boolean = false)
data class RoadNetwork(val revision: String, val provenance: RoadProvenance, val segments: List<RoadSegment>)
enum class MatchStatus { MATCHED, NO_MAP, INVALID_MAP, INVALID_POSITION, ACCURACY_UNKNOWN, ACCURACY_POOR, OUTSIDE_MAP, AMBIGUOUS, EXPIRED }
data class RoadMatch(val atMs: Long, val status: MatchStatus, val roadId: String? = null,
    val distanceM: Double? = null, val roadHeadingDeg: Double? = null,
    val entryNode: String? = null, val exitNode: String? = null, val speedLimitMps: Double? = null)
enum class ManeuverKind { STRAIGHT, CURVE, LEFT_TURN, RIGHT_TURN, LEFT_TURN_CANDIDATE, RIGHT_TURN_CANDIDATE,
    LANE_CHANGE_LEFT_CANDIDATE, LANE_CHANGE_RIGHT_CANDIDATE, LOW_SPEED_MANEUVER, REVERSE_MANEUVER, INCOMPLETE, UNKNOWN }
enum class EvidenceStrength { SUPPORTED, LIMITED, INSUFFICIENT }
data class DetectedManeuver(val id: String, val kind: ManeuverKind, val startMs: Long, val endMs: Long,
    val headingChangeDeg: Double, val evidence: Map<String,String>, val strength: EvidenceStrength,
    val missing: Set<String> = emptySet())
data class ChannelQuality(val channel: String, val eligibleMs: Long, val totalMs: Long, val samples: Int,
    val reasons: Set<String>) {
    val coverage get() = if (totalMs > 0) eligibleMs.toDouble() / totalMs else 0.0
}
data class PracticeAnalysis(val maneuvers: List<DetectedManeuver>, val roadMatches: List<RoadMatch>,
    val quality: List<ChannelQuality>, val confirmedTurns: List<TurnManeuver>, val turnDetectionComplete: Boolean)
enum class ReviewPriority { PRACTICE, REVIEW, CONTEXT, DATA_CHECK }
data class LearningFeedback(val id: String, val startMs: Long, val endMs: Long, val priority: ReviewPriority,
    val title: String, val observation: String, val nextPractice: String, val strength: EvidenceStrength,
    val evidence: Map<String,String>, val missing: Set<String>, val eventKinds: Set<EventKind> = emptySet())

/** Experimental geometry/segmentation thresholds, not calibrated accuracy probabilities. */
data class PracticeConfig(val positionMaxAgeMs: Long = 2_000, val matchDistanceM: Double = 25.0,
    val ambiguityMarginM: Double = 3.0, val maxAccuracyM: Double = 15.0,
    val minMovingSpeedMps: Double = 1.0, val headingRateDegPerSec: Double = 3.0,
    val settleMs: Long = 1_000, val turnMinDeg: Double = 40.0, val turnMaxDeg: Double = 135.0,
    val laneReturnDeg: Double = 10.0, val laneExcursionDeg: Double = 5.0, val reviewMergeGapMs: Long = 1_000) {
    init {
        require(positionMaxAgeMs > 0 && settleMs > 0 && reviewMergeGapMs >= 0)
        require(listOf(matchDistanceM,ambiguityMarginM,maxAccuracyM,minMovingSpeedMps,headingRateDegPerSec,
            turnMinDeg,turnMaxDeg,laneReturnDeg,laneExcursionDeg).all { it.isFinite() && it > 0 })
        require(turnMinDeg < turnMaxDeg && turnMaxDeg < 180 && laneReturnDeg < turnMinDeg)
    }
}
