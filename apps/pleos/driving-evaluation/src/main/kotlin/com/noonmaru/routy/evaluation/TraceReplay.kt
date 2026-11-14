package com.noonmaru.routy.evaluation

import com.squareup.moshi.Moshi
import java.io.File

typealias JsonObject = Map<String, Any?>

object JsonDocuments {
    private val moshi = Moshi.Builder().build()
    fun read(text: String): JsonObject = requireNotNull(moshi.adapter(Map::class.java).fromJson(text)).jsonObject()
    fun write(value: Any): String = moshi.adapter(Any::class.java).indent("  ").toJson(value)
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
        require(document.string("ruleVersion") == EvaluationConfig().ruleVersion) { "Trace rule version differs; an explicit migration/config is required" }
        require(document.string("engineVersion") == "0.3.0") { "Unsupported captured engine version" }
        require(document.string("origin") == DataOrigin.LIVE.name) { "Expected a LIVE recorder trace; synthetic fixtures have a separate entry point" }
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
            })
    }

    fun capturedContexts(document: JsonObject): List<ContextFinding> = document.objects("contextFindings").map {
        ContextFinding(EventKind.valueOf(it.string("event")), it.long("startMs"), it.long("endMs"),
            FusionContext.valueOf(it.string("context")),
            it["evidence"].jsonObject().mapValues { (_, value) -> value as? String ?: error("Expected string evidence") },
            it.strings("missing").toSet(), it.boolean("lanePositionKnown"))
    }

    fun verifyCaptured(document: JsonObject, report: EvaluationReport) {
        check(report.contextFindings == capturedContexts(document)) { "Replayed context results differ from the captured results" }
    }

    fun summary(report: EvaluationReport): JsonObject = mapOf(
        "engineVersion" to report.engineVersion, "ruleVersion" to report.config.ruleVersion, "origin" to report.origin.name,
        "startMs" to report.startMs, "endMs" to report.endMs,
        "telemetry" to mapOf("channels" to report.telemetry.channels, "readings" to report.telemetry.readings, "updates" to report.telemetry.updates, "truncated" to report.telemetry.truncated),
        "events" to report.events.map { mapOf("kind" to it.kind.name, "startMs" to it.startMs, "endMs" to it.endMs, "reviewOnly" to it.reviewOnly, "evidence" to it.evidence) },
        "assessments" to report.assessments.map { mapOf("category" to it.category.name, "status" to it.status.name,
            "eligibleMs" to it.eligibleMs, "excludedMs" to it.excludedMs, "reasons" to it.reasons.map { reason -> reason.name }.sorted()) },
        "contextFindings" to report.contextFindings.map { mapOf("event" to it.event.name, "startMs" to it.startMs,
            "endMs" to it.endMs, "context" to it.context.name, "evidence" to it.evidence,
            "missing" to it.missing.sorted(), "lanePositionKnown" to it.lanePositionKnown) })
}
