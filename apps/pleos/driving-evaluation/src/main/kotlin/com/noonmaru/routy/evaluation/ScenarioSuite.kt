package com.noonmaru.routy.evaluation

/** Fixed synthetic inputs and a separately declared expected result; never a driving accuracy claim. */
data class DemoScenario(val definition: JsonObject) {
    val id = definition.string("id")
    override fun toString() = id

    fun session(): DrivingSession {
        val ticks = (0L..8_000L step 100).toList()
        val speed = definition.number("speedMps")
        val steering = definition.string("steering")
        val quality = definition["quality"] as? String
        val speeds = ticks.map { Observation(it, speed) }.let {
            if (quality == "duplicate-speed") listOf(it.first()) + it else it
        }
        val turn = definition["turn"] as? String
        val signals = ticks.filter { it % 500L == 0L }.map {
            Observation(it, SignalSelection.valueOf(definition.string("signal")))
        }
        val readings = ticks.filter { it % 500L == 0L }.flatMap { at ->
            buildList {
                add(SensorReading("AAOS.GEAR_SELECTION", at, ReadingKind.SNAPSHOT, state = definition.string("gear")))
                add(SensorReading("Vehicle.TurnSignal.getCurrentTurnSignalState", at, ReadingKind.SNAPSHOT, state = definition.string("signal")))
                if (quality != "missing-brake") add(SensorReading("Vehicle.Brake.getCurrentBrakePedalStatus", at, ReadingKind.SNAPSHOT, flag = definition.boolean("brake")))
                add(SensorReading("Vehicle.DrivingMode.getCruiseControlState", at, ReadingKind.SNAPSHOT,
                    state = definition["cruise"] as? String ?: "ENABLED"))
            }
        }
        return DrivingSession(0, 8_000, DataOrigin.SYNTHETIC, speeds = speeds,
            steeringAngles = ticks.map { at -> Observation(at, when (steering) {
                "steady" -> 0.0
                "wave" -> listOf(0.0, 30.0, 60.0, 30.0, 0.0, -30.0, -60.0, -30.0)[((at / 100) % 8).toInt()]
                else -> error("Unknown steering profile: $steering")
            }) },
            steeringTimeBasis = SteeringTimeBasis.PROVIDER,
            steeringGaps = if (quality == "steering-gap") listOf(TimeRange(0, 8_000)) else emptyList(),
            speedGaps = if (quality == "speed-gap") listOf(TimeRange(0, 8_000)) else emptyList(),
            telemetry = readings,
            signals = if (turn != null) signals else emptyList(),
            signalSemantics = if (turn != null) SignalSemantics.SELECTION_STATE else SignalSemantics.UNKNOWN,
            turns = if (turn == null) emptyList() else listOf(TurnManeuver(id, 5_000, 7_000, TurnDirection.valueOf(turn),
                setOf(TurnEvidence.MATCHED_ROAD_TRANSITION, TurnEvidence.OBSERVED_HEADING_CHANGE))),
            turnDetectionComplete = turn != null)
    }

    fun violations(report: EvaluationReport): List<String> = buildList {
        val expectedEvents = definition.strings("events").map(EventKind::valueOf).toSet()
        val actualEvents = report.events.map { it.kind }.toSet()
        if (expectedEvents != actualEvents) add("event kinds expected=$expectedEvents actual=$actualEvents")
        val expectedContexts = definition.strings("contexts").map(FusionContext::valueOf).toSet()
        val actualContexts = report.contextFindings.map { it.context }.toSet()
        if (expectedContexts != actualContexts) add("contexts expected=$expectedContexts actual=$actualContexts")
        if (report.events.any { !it.reviewOnly }) add("A review event was promoted to a fault finding")
        if (report.contextFindings.any { it.lanePositionKnown }) add("Lane position was invented")
        for (category in listOf(Category.LANE_KEEPING, Category.FOLLOWING_DISTANCE, Category.TRAFFIC_SIGNAL_RESPONSE)) {
            if (report.assessments.single { it.category == category }.status != AssessmentStatus.NOT_ASSESSED)
                add("$category must remain unassessed")
        }
        val expectedAdequate = definition["adequatelySignalled"] as? Number
        if (expectedAdequate != null && report.turns.adequatelySignalled != expectedAdequate.toInt()) add("Unexpected adequate turn count")
        if (definition["turn"] == null && report.assessments.single { it.category == Category.TURN_SIGNAL_USE }.status != AssessmentStatus.NOT_ASSESSED)
            add("Steering/indicator inputs alone must not become confirmed turns")
        if (definition["quality"] == "steering-gap" && report.steering.eligibleMs != 0L) add("Steering gap was assessed")
    }
}

object DemoScenarios {
    fun document(): JsonObject = JsonDocuments.read(requireNotNull(javaClass.getResourceAsStream("/scenarios/demo-v1.json"))
        .bufferedReader(Charsets.UTF_8).use { it.readText() })
    fun cases(): List<DemoScenario> = document().objects("cases").map(::DemoScenario)
}
