package org.eowoo.routy.evaluation

object PracticeCodec {
    fun network(value: JsonObject): RoadNetwork = RoadNetwork(value.string("revision"),RoadProvenance.valueOf(value.string("provenance")),value.objects("segments").map { road ->
        RoadSegment(road.string("id"),road.string("fromNode"),road.string("toNode"),road.objects("geometry").map { GeoPoint(it.number("latitude"),it.number("longitude")) },
            if (road["speedLimitMps"] == null) null else road.number("speedLimitMps"),road.long("validUntilMs"),road.boolean("bidirectional"))
    })
    /** Provider file expiry uses UTC epoch; converted once into the session monotonic clock. */
    fun liveNetwork(text: String, atMs: Long, epochMs: Long, sessionStartEpochMs: Long = epochMs): RoadNetwork {
        val value=JsonDocuments.read(text)
        require(value.string("provenance") == RoadProvenance.VERIFIED_MAP.name) { "Live capture requires provider-verified map data" }
        require(value.long("effectiveFromEpochMs") <= sessionStartEpochMs) { "Map became effective after capture began; no retrospective application" }
        val remaining=(value.long("expiresAtEpochMs")-epochMs).coerceIn(-86_400_000,86_400_000)
        return network(value + ("segments" to value.objects("segments").map { it + ("validUntilMs" to (atMs+remaining).coerceAtLeast(0)) }))
    }
    fun encodeNetwork(value: RoadNetwork): JsonObject = mapOf("revision" to value.revision,"provenance" to value.provenance.name,
        "segments" to value.segments.map { road -> mapOf("id" to road.id,"fromNode" to road.fromNode,"toNode" to road.toNode,
            "geometry" to road.geometry.map { mapOf("latitude" to it.latitude,"longitude" to it.longitude) },
            "speedLimitMps" to road.speedLimitMps,"validUntilMs" to road.validUntilMs,"bidirectional" to road.bidirectional) })
    fun positions(value: JsonObject): List<Observation<PositionFix>> = value.objects("positions").map { row ->
        val fix = row["value"]?.jsonObject()?.let { PositionFix(GeoPoint(it.number("latitude"),it.number("longitude")),
            if (it["headingDeg"] == null) null else it.number("headingDeg"),if (it["accuracyM"] == null) null else it.number("accuracyM"),it.string("source")) }
        Observation(row.long("atMs"),fix,row.boolean("available"))
    }
    fun encodePositions(value: List<Observation<PositionFix>>): List<JsonObject> = value.map { r -> mapOf("atMs" to r.atMs,"available" to r.available,"value" to r.value?.let {
        mapOf("latitude" to it.point.latitude,"longitude" to it.point.longitude,"headingDeg" to it.headingDeg,"accuracyM" to it.accuracyM,"source" to it.source) }) }
    fun summary(analysis: PracticeAnalysis): JsonObject = mapOf(
        "turnDetectionComplete" to analysis.turnDetectionComplete,
        "quality" to analysis.quality.map { mapOf("channel" to it.channel,"eligibleMs" to it.eligibleMs,"totalMs" to it.totalMs,"samples" to it.samples,"reasons" to it.reasons.sorted()) },
        "roadMatches" to analysis.roadMatches.map { mapOf("atMs" to it.atMs,"status" to it.status.name,"roadId" to it.roadId,"distanceM" to it.distanceM,"roadHeadingDeg" to it.roadHeadingDeg,"entryNode" to it.entryNode,"exitNode" to it.exitNode,"speedLimitMps" to it.speedLimitMps) },
        "maneuvers" to analysis.maneuvers.map { mapOf("id" to it.id,"kind" to it.kind.name,"startMs" to it.startMs,"endMs" to it.endMs,"headingChangeDeg" to it.headingChangeDeg,"evidence" to it.evidence,"strength" to it.strength.name,"missing" to it.missing.sorted()) })
    fun feedback(rows: List<LearningFeedback>): List<JsonObject> = rows.map { mapOf("id" to it.id,"startMs" to it.startMs,"endMs" to it.endMs,
        "priority" to it.priority.name,"title" to it.title,"observation" to it.observation,"nextPractice" to it.nextPractice,
        "strength" to it.strength.name,"evidence" to it.evidence,"missing" to it.missing.sorted(),"eventKinds" to it.eventKinds.map { k -> k.name }.sorted()) }
}
