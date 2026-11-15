package org.eowoo.routy.evaluation

import com.squareup.moshi.Moshi
import java.io.File

typealias JsonObject = Map<String, Any?>

object JsonDocuments {
    private val moshi = Moshi.Builder().build()
    fun read(text: String): JsonObject = requireNotNull(moshi.adapter(Map::class.java).fromJson(text)).jsonObject()
    fun write(value: Any): String = moshi.adapter(Any::class.java).indent("  ").toJson(value)
    fun compact(value: Any): String = moshi.adapter(Any::class.java).toJson(value)
}

internal fun Any?.jsonObject(): JsonObject {
    val map = this as? Map<*, *> ?: error("Expected a JSON object")
    return map.entries.associate { (key, value) -> (key as? String ?: error("Expected a string key")) to value }
}
internal fun JsonObject.string(key: String) = this[key] as? String ?: error("Expected string: $key")
internal fun JsonObject.boolean(key: String) = this[key] as? Boolean ?: error("Expected boolean: $key")
internal fun JsonObject.number(key: String) = (this[key] as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: error("Expected finite number: $key")
internal fun JsonObject.long(key: String): Long = number(key).let {
    require(it >= 0 && it <= 9_007_199_254_740_991.0 && it % 1.0 == 0.0) { "Expected non-negative exact integer: $key" }
    it.toLong()
}
internal fun JsonObject.objects(key: String) = (this[key] as? List<*>)?.map { it.jsonObject() } ?: error("Expected object array: $key")
internal fun JsonObject.strings(key: String) = (this[key] as? List<*>)?.map { it as? String ?: error("Expected string in $key") } ?: error("Expected string array: $key")

/** Decodes the debug recorder's versioned input. Cached receipt/provider timestamps stay distinct. */
object TraceReplay {
    fun read(file: File): JsonObject {
        require(file.length() in 1..67_108_864) { "Trace must be a non-empty JSON file under 64MiB" }
        return JsonDocuments.read(file.readText())
    }

    fun session(document: JsonObject): DrivingSession {
        val legacy = document.string("engineVersion") == "0.3.0" && document.string("ruleVersion") == "experimental-0.3"
        val current = document.string("engineVersion") in setOf("0.4.0","0.5.0","0.6.0","0.7.0") && document.string("ruleVersion") == EvaluationConfig().ruleVersion
        require(legacy || current) { "Unsupported captured engine/rule pair; an explicit migration is required" }
        if(document.string("engineVersion")=="0.6.0") require(document.string("environmentRuleVersion")=="environment-0.2") { "Unsupported environment rule version" }
        if(document.string("engineVersion")=="0.7.0") require(document.string("environmentRuleVersion")=="environment-0.3") { "Unsupported environment rule version" }
        if (current) {
            require(document.string("configSignature") == EvaluationConfig().toString()) { "Custom evaluation config requires explicit replay support" }
            require(document.string("practiceConfigSignature") == PracticeConfig().toString()) { "Custom practice config requires explicit replay support" }
        }
        if(document.string("origin")==DataOrigin.SYNTHETIC.name) {
            require(document.string("engineVersion")=="0.7.0" && document.long("schemaVersion")==1L)
            return ScenarioReview.session(EnvironmentEvaluation.decode(JsonDocuments.write(mapOf("frames" to document["frames"]))))
        }
        require(document.string("origin") == DataOrigin.LIVE.name) { "Expected LIVE or versioned synthetic recorder trace" }
        fun observations(key: String) = document.objects(key).map {
            Observation(it.long("atMs"), if (it["value"] == null) null else it.number("value"), it.boolean("available"))
        }
        fun ranges(key: String) = document.objects(key).map { TimeRange(it.long("startMs"), it.long("endMs")) }
        return DrivingSession(document.long("startMs"), document.long("endMs"), DataOrigin.RECORDED,
            speeds = observations("speed"), steeringAngles = observations("steering"),
            speedGaps = ranges("speedGaps"), steeringGaps = ranges("steeringGaps"),
            steeringTimeBasis = SteeringTimeBasis.valueOf(document.string("steeringTimeBasis")),
            telemetryTruncated = document.boolean("truncated"),
            telemetry = document.objects("readings").map { r ->
                SensorReading(r.string("signal"), r.long("atMs"), ReadingKind.valueOf(r.string("kind")),
                    number = if (r["number"] == null) null else r.number("number"),
                    flag = if (r["flag"] == null) null else r.boolean("flag"),
                    state = if (r["state"] == null) null else r.string("state"),
                    fields = r["fields"].jsonObject().mapValues { (_, v) -> v as? String ?: error("Expected string field") },
                    available = r.boolean("available"), providerTimestampMs = if (r["providerTimestampMs"] == null) null else r.long("providerTimestampMs"))
            }, sensorAvailability = document.objects("availability").map {
                SensorAvailability(it.string("signal"), SensorAccess.valueOf(it.string("access")), it.string("detail"))
            },
            positions=if (current) PracticeCodec.positions(document) else emptyList(),
            positionGaps=if (current) ranges("positionGaps") else emptyList(),
            positionTimeBasis=if (current) SteeringTimeBasis.valueOf(document.string("positionTimeBasis")) else SteeringTimeBasis.UNKNOWN,
            roadNetwork=if (current) document["roadNetwork"]?.jsonObject()?.let(PracticeCodec::network) else null,
            hazardStateKnown=if (current) document.boolean("hazardStateKnown") else true,
            environment=if(document.string("engineVersion") in setOf("0.5.0","0.6.0","0.7.0")) EnvironmentIntegration.decode(document.objects("environment")) else emptyList(),
            environmentRuleVersion=when(document.string("engineVersion")) { "0.5.0" -> "environment-0.1";"0.6.0" -> "environment-0.2";else -> "environment-0.3" },
            signalSemantics=if (current) SignalSemantics.valueOf(document.string("signalSemantics")) else SignalSemantics.UNKNOWN,
            turnDetectionComplete=if (current) document.boolean("turnDetectionComplete") else false,
            signals=if (current) document.objects("signals").map { Observation(it.long("atMs"),it["value"]?.let { v -> SignalSelection.valueOf(v as String) },it.boolean("available")) } else emptyList(),
            roads=if (current) document.objects("roads").map { r -> Observation(r.long("atMs"),r["value"]?.jsonObject()?.let {
                RoadContext(it.string("matchedRoadId"),it.string("speedLimitRoadId"),it.number("speedLimitMps"),it.long("validUntilMs")) },r.boolean("available")) } else emptyList(),
            turns=if (current) document.objects("turns").map { TurnManeuver(it.string("id"),it.long("startMs"),it.long("endMs"),TurnDirection.valueOf(it.string("direction")),it.strings("evidence").map(TurnEvidence::valueOf).toSet()) } else emptyList())
    }

    fun capturedContexts(document: JsonObject): List<ContextFinding> = document.objects("contextFindings").map {
        ContextFinding(EventKind.valueOf(it.string("event")), it.long("startMs"), it.long("endMs"),
            FusionContext.valueOf(it.string("context")),
            it["evidence"].jsonObject().mapValues { (_, value) -> value as? String ?: error("Expected string evidence") },
            it.strings("missing").toSet(), it.boolean("lanePositionKnown"))
    }

    fun verifyCaptured(document: JsonObject, report: EvaluationReport) {
        check(report.contextFindings == capturedContexts(document)) { "Replayed context results differ from the captured results" }
        if (document.string("engineVersion") in setOf("0.4.0","0.5.0","0.6.0","0.7.0")) {
            val actual = JsonDocuments.read(JsonDocuments.write(summary(report))) - "origin"
            check(document["analysis"].jsonObject()-"origin" == actual) { "Replayed practice, quality, feedback or event results differ from the captured results" }
        }
    }

    fun recording(input: DrivingSession, report: EvaluationReport): JsonObject {
        require(input.origin == DataOrigin.LIVE && report.origin == DataOrigin.LIVE)
        val session=PracticeInputs.normalize(input)
        fun observations(rows: List<Observation<Double>>) = rows.map { mapOf("atMs" to it.atMs,"value" to it.value,"available" to it.available) }
        fun ranges(rows: List<TimeRange>) = rows.map { mapOf("startMs" to it.startMs,"endMs" to it.endMs) }
        return mapOf("engineVersion" to report.engineVersion,"ruleVersion" to report.config.ruleVersion,"origin" to session.origin.name,
            "configSignature" to report.config.toString(),"practiceConfigSignature" to report.practiceConfig.toString(),
            "startMs" to session.startMs,"endMs" to session.endMs,"truncated" to session.telemetryTruncated,
            "speed" to observations(session.speeds),"steering" to observations(session.steeringAngles),
            "speedGaps" to ranges(session.speedGaps),"steeringGaps" to ranges(session.steeringGaps),"steeringTimeBasis" to session.steeringTimeBasis.name,
            "positions" to PracticeCodec.encodePositions(session.positions),"positionGaps" to ranges(session.positionGaps),"positionTimeBasis" to session.positionTimeBasis.name,
            "roadNetwork" to session.roadNetwork?.let(PracticeCodec::encodeNetwork),"hazardStateKnown" to session.hazardStateKnown,
            "signalSemantics" to session.signalSemantics.name,"turnDetectionComplete" to session.turnDetectionComplete,
            "signals" to session.signals.map { mapOf("atMs" to it.atMs,"value" to it.value?.name,"available" to it.available) },
            "roads" to session.roads.map { r -> mapOf("atMs" to r.atMs,"available" to r.available,"value" to r.value?.let { mapOf("matchedRoadId" to it.matchedRoadId,"speedLimitRoadId" to it.speedLimitRoadId,"speedLimitMps" to it.speedLimitMps,"validUntilMs" to it.validUntilMs) }) },
            "turns" to session.turns.map { mapOf("id" to it.id,"startMs" to it.startMs,"endMs" to it.endMs,"direction" to it.direction.name,"evidence" to it.evidence.map { e -> e.name }.sorted()) },
            "readings" to session.telemetry.map { mapOf("signal" to it.signal,"atMs" to it.atMs,"kind" to it.kind.name,"number" to it.number,"flag" to it.flag,"state" to it.state,"fields" to it.fields,"available" to it.available,"providerTimestampMs" to it.providerTimestampMs) },
            "availability" to session.sensorAvailability.map { mapOf("signal" to it.signal,"access" to it.access.name,"detail" to it.detail) },
            "contextFindings" to summary(report)["contextFindings"],"analysis" to summary(report)) +
            (if(session.environment.isEmpty()) emptyMap() else mapOf("environment" to EnvironmentIntegration.encode(session.environment))) +
            (if(report.engineVersion in setOf("0.6.0","0.7.0")) mapOf("environmentRuleVersion" to session.environmentRuleVersion) else emptyMap())
    }

    fun summary(report: EvaluationReport): JsonObject = mapOf(
        "engineVersion" to report.engineVersion, "ruleVersion" to report.config.ruleVersion, "origin" to report.origin.name,
        "startMs" to report.startMs, "endMs" to report.endMs,
        "telemetry" to mapOf("channels" to report.telemetry.channels, "readings" to report.telemetry.readings, "updates" to report.telemetry.updates, "truncated" to report.telemetry.truncated),
        "events" to report.events.map { mapOf("kind" to it.kind.name, "startMs" to it.startMs, "endMs" to it.endMs, "reviewOnly" to it.reviewOnly, "evidence" to it.evidence) },
        "assessments" to report.assessments.map { mapOf("category" to it.category.name, "status" to it.status.name,
            "eligibleMs" to it.eligibleMs, "excludedMs" to it.excludedMs, "reasons" to it.reasons.map { reason -> reason.name }.sorted()) },
        "practice" to PracticeCodec.summary(report.practice),"feedback" to PracticeCodec.feedback(report.feedback),
        "contextFindings" to report.contextFindings.map { mapOf("event" to it.event.name, "startMs" to it.startMs,
            "endMs" to it.endMs, "context" to it.context.name, "evidence" to it.evidence,
            "missing" to it.missing.sorted(), "lanePositionKnown" to it.lanePositionKnown) }) +
        (report.environment?.let { result -> mapOf("environment" to EnvironmentReviewCodec.summary(result)) } ?: emptyMap())
}
