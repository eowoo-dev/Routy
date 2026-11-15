package org.eowoo.routy.evaluation

enum class ReadingKind { SNAPSHOT, UPDATE, GAP }
enum class SensorAccess { PROBING, SUBSCRIBED, READABLE, LIVE, UNSUPPORTED, PERMISSION_DENIED, TIMEOUT, ERROR, SKIPPED, NO_PUBLIC_SDK }

/** SDK receipt time is not advertised as hardware generation time. Fields retain native names/units. */
data class SensorReading(
    val signal: String,
    val atMs: Long,
    val kind: ReadingKind,
    val number: Double? = null,
    val flag: Boolean? = null,
    val state: String? = null,
    val fields: Map<String, String> = emptyMap(),
    val available: Boolean = true,
    val providerTimestampMs: Long? = null,
)

data class SensorAvailability(val signal: String, val access: SensorAccess, val detail: String)
data class TelemetrySummary(val channels: Int, val readings: Int, val updates: Int, val truncated: Boolean,
    val availability: List<SensorAvailability>)

enum class FusionContext { STATIONARY_MANEUVER, REVERSE_MANEUVER, CRUISE_CONTROL_ACTIVE, SIGNALLED_STEERING,
    BRAKING_MANEUVER, UNSIGNALLED_CORRECTION_CANDIDATE, UNRESOLVED, INCONSISTENT_INPUTS }
data class ContextFinding(val event: EventKind, val startMs: Long, val endMs: Long, val context: FusionContext,
    val evidence: Map<String, String>, val missing: Set<String>, val lanePositionKnown: Boolean = false)

/** Closed demo read policy: never discover private/vendor methods or invoke setters/control calls. */
object SensorReadPolicy {
    val domains = listOf("Brake", "CarInfo", "Display", "Door", "DrivingMode", "EvBattery", "Fuel", "HVAC",
        "Light", "Odometer", "Safety", "Seat", "SideMirror", "SteeringWheel", "Tire", "Toll", "TurnSignal", "Window", "Wiper")
    private val blocked = setOf("CarInfo.getCapabilityInfo", "Safety.checkTelltalesCapabilities", "Safety.getTelltalesInfo",
        "Safety.registerTelltales", "Safety.unregisterTelltales", "Tire.checkTirePressureCapabilities", "Tire.getTirePressure",
        "Tire.registerTirePressure", "Tire.unregisterTirePressure") // Current official sample's non-public/deprecated policy.
    fun allowed(domain: String, method: String): Boolean = domain in domains && "$domain.$method" !in blocked &&
        !method.contains('$') && !method.contains("Internal", true) && !method.contains("Private", true) &&
        !method.contains("getAnd", true) && method !in setOf("getVINInfo", "getCRNInfo") &&
        (method.startsWith("check") && method.endsWith("Capabilities") ||
            method.startsWith("get") || method.startsWith("has") || method.startsWith("is") ||
            method.startsWith("register") || method.startsWith("unregister"))
    fun feature(method: String): String = method.removePrefix("unregister").removePrefix("register")
        .removePrefix("check").removePrefix("getCurrent").removePrefix("get").removePrefix("has").removePrefix("is")
        .removePrefix("Current").removeSuffix("Capabilities").removeSuffix("Capability").removeSuffix("Listener")
        .removeSuffix("State").let { feature -> when (feature) {
            "SeatAirbagEnabled" -> "Airbag"
            "SeatAirbagBeenDeployed", "SeatAirbagDeployed" -> "AirbagDeployed"
            "SeatBeltNotBuckledWarning" -> "SeatNotBuckledWarning"
            "SeatHeadrestHeightAftPosition" -> "SeatHeadrestHeightPosition"
            "HvacSeatTemperature" -> "SeatTemperature"
            "HvacSeatVentilation" -> "SeatVentilation"
            "LowWasherFluidWaring" -> "LowWasherFluidWarning"
            else -> feature
        } }
}

internal fun fuseContext(session: DrivingSession, events: List<EvaluationEvent>): List<ContextFinding> {
    val speeds = Timeline(session.speeds, session, 1_000, session.speedGaps) { value, _ ->
        if (!value.isFinite() || value < 0 || value > 100) ExclusionReason.INVALID_VALUE else null
    }
    fun latest(prefix: String, at: Long): SensorReading? {
        if (session.telemetryTruncated) return null
        val samples = session.telemetry.filter { it.signal == prefix && it.atMs in session.startMs..at }
        val latestAt = samples.maxOfOrNull { it.atMs } ?: return null
        if (at - latestAt >= 2_000) return null
        val current = samples.filter { it.atMs == latestAt }
        if (current.map { listOf(it.number, it.flag, it.state, it.available) }.distinct().size != 1) return null
        return current.first().takeIf { it.available }
    }
    return events.filter { it.kind in setOf(EventKind.RAPID_STEERING, EventKind.REPEATED_STEERING_REVERSALS, EventKind.RAPID_DECELERATION) }.map { event ->
        val evidence = linkedMapOf("interpretation" to "co-occurring observations; not proof of lane position or driver fault")
        val missing = linkedSetOf<String>()
        // Match at the event midpoint; never use an observation from the future to fill an earlier moment.
        val at = event.startMs + (event.endMs - event.startMs) / 2
        val speed = speeds.read(at).value
        val gear = latest("AAOS.GEAR_SELECTION", at)?.state
        val brake = latest("Vehicle.Brake.getCurrentBrakePedalStatus", at)?.flag
        val turn = latest("Vehicle.TurnSignal.getCurrentTurnSignalState", at)?.state
        val cruise = latest("Vehicle.DrivingMode.getCruiseControlState", at)?.state
        fun evidence(key: String, value: Any?) { if (value == null) missing += key else evidence[key] = value.toString() }
        evidence("speedMps", speed); evidence("gear", gear); evidence("brakePressed", brake)
        evidence("turnSignal", turn); evidence("cruiseControl", cruise)
        val context = when {
            speed != null && speed > 0.5 && gear == "PARK" -> FusionContext.INCONSISTENT_INPUTS
            gear == "REVERSE" -> FusionContext.REVERSE_MANEUVER
            speed != null && speed >= 0 && speed <= 0.5 -> FusionContext.STATIONARY_MANEUVER
            cruise == "ACTIVATED" -> FusionContext.CRUISE_CONTROL_ACTIVE // Does not imply lateral steering assistance.
            turn in setOf("LEFT", "RIGHT") -> FusionContext.SIGNALLED_STEERING
            brake == true -> FusionContext.BRAKING_MANEUVER
            event.kind == EventKind.REPEATED_STEERING_REVERSALS && speed != null && speed >= 5 && turn == "OFF" && brake == false -> FusionContext.UNSIGNALLED_CORRECTION_CANDIDATE
            else -> FusionContext.UNRESOLVED
        }
        evidence["contextTimeMs"] = at.toString()
        ContextFinding(event.kind, event.startMs, event.endMs, context, evidence, missing)
    }
}
