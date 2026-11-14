package com.noonmaru.routy.evaluation

import kotlin.math.*

/** Synthetic coordinates/map, never presented as live road data. Ground truth lives in tests. */
object PracticeScenarios {
    data class Case(val definition: JsonObject) {
        val id = definition.string("id")
        override fun toString() = id
        fun violations(report: EvaluationReport): List<String> = buildList {
            if (report.practice.maneuvers.none { it.kind.name == definition.string("requiredKind") })
                add("Missing ${definition.string("requiredKind")}; got ${report.practice.maneuvers.map { it.kind }}")
            if (report.events.map { it.kind.name }.toSet() != definition.strings("events").toSet()) add("Unexpected events: ${report.events.map { it.kind }}")
            if (report.feedback.none { definition.string("feedback") in it.title || definition.string("feedback") in it.observation }) add("Expected learning feedback was not produced")
            if (report.practice.quality.any { it.eligibleMs !in 0..it.totalMs }) add("Invalid quality coverage")
            if (report.assessments.single { it.category == Category.LANE_KEEPING }.status != AssessmentStatus.NOT_ASSESSED) add("Lane keeping was invented")
            if (report.events.any { !it.reviewOnly }) add("Review observation became a fault finding")
        }
    }
    fun cases(): List<Case> = JsonDocuments.read(requireNotNull(javaClass.getResourceAsStream("/scenarios/practice-v1.json"))
        .bufferedReader(Charsets.UTF_8).use { it.readText() }).objects("cases").map(::Case)
    val ids = listOf("left-prepared","right-prepared","left-missing","left-late","left-opposite","left-interrupted",
        "curve","lane-left-candidate","lane-right-candidate","parking","reverse","heading-only","ambiguous-map",
        "position-gap","incomplete-turn","hazard-unknown","straight-corrections","mapped-speed-excess")
    fun session(id: String): DrivingSession {
        require(id in ids)
        val end = if (id == "incomplete-turn") 7_000L else 12_000L
        val speed = when (id) { "parking" -> 0.5; "reverse" -> -2.0; "straight-corrections" -> 10.0; "mapped-speed-excess" -> 15.0; else -> 5.0 }
        val sign = if (id.startsWith("right") || id == "lane-right-candidate") 1 else -1
        val base = GeoPoint(37.0,127.0)
        fun point(x: Double,y: Double) = GeoPoint(base.latitude+y/111_195,base.longitude+x/(111_195*cos(Math.toRadians(base.latitude))))
        fun heading(at: Long): Double {
            val t=at/1000.0
            val angle = when {
                id in setOf("straight-corrections","mapped-speed-excess") -> 0.0
                id.startsWith("lane-") -> when { t<4 -> 0.0; t<6 -> (t-4)*7.5; t<8 -> (8-t)*7.5; else -> 0.0 }
                else -> ((t-4)/4).coerceIn(0.0,1.0)*90
            }
            return (sign*angle+360)%360
        }
        var x=0.0;var y=0.0
        val positions = (0L..end step 500).map { at ->
            if (at > 0) {
                val h = heading(at-250)
                x += sin(Math.toRadians(h))*speed*0.5; y += cos(Math.toRadians(h))*speed*0.5
            }
            Observation(at,PositionFix(point(x,y),heading(at),0.5,"synthetic-practice-v1"))
        }
        val after = positions.last().value!!.point
        val straight = id.startsWith("lane-") || id in setOf("straight-corrections","mapped-speed-excess")
        val network = when {
            id == "heading-only" -> null
            id == "curve" -> RoadNetwork("practice-curve-v1",RoadProvenance.SYNTHETIC,listOf(
                RoadSegment("curve","A","B",positions.map { it.value!!.point },10.0,20_000)))
            straight -> RoadNetwork("practice-straight-v1",RoadProvenance.SYNTHETIC,listOf(
                RoadSegment("straight","A","B",listOf(point(0.0,-30.0),point(0.0,250.0)),10.0,20_000)))
            else -> {
                val node = GeoPoint(after.latitude,base.longitude)
                val roads = listOf(RoadSegment("incoming","A","J",listOf(point(0.0,-30.0),node),10.0,20_000),
                    RoadSegment("outgoing","J","B",listOf(node,GeoPoint(node.latitude,after.longitude+sign*50/(111_195*cos(Math.toRadians(base.latitude))))),10.0,20_000))
                RoadNetwork("practice-junction-v1",RoadProvenance.SYNTHETIC,
                    if (id == "ambiguous-map") roads + roads.map { it.copy(id="duplicate-${it.id}") } else roads)
            }
        }
        val ticks = (0L..end step 100).toList()
        val wanted = if (sign < 0) SignalSelection.LEFT else SignalSelection.RIGHT
        val signals = (0L..end step 500).map { at -> Observation(at,when (id) {
            "left-missing" -> SignalSelection.OFF
            "left-late" -> if (at >= 3_500) wanted else SignalSelection.OFF
            "left-opposite" -> SignalSelection.RIGHT
            "left-interrupted" -> if (at < 3_500) wanted else SignalSelection.OFF
            else -> if (id in setOf("straight-corrections","mapped-speed-excess")) SignalSelection.OFF else wanted
        }) }
        return DrivingSession(0,end,DataOrigin.SYNTHETIC,speeds=ticks.map { Observation(it,speed) },
            steeringAngles=ticks.map { at -> Observation(at,if (id == "straight-corrections") listOf(0.0,30.0,60.0,30.0,0.0,-30.0,-60.0,-30.0)[((at/100)%8).toInt()] else 0.0) },
            steeringTimeBasis=SteeringTimeBasis.PROVIDER,positions=positions,positionTimeBasis=SteeringTimeBasis.PROVIDER,
            positionGaps=if (id == "position-gap") listOf(TimeRange(5_000,7_000)) else emptyList(),roadNetwork=network,
            signals=signals,signalSemantics=SignalSemantics.SELECTION_STATE,hazardStateKnown=id != "hazard-unknown",
            telemetry=(0L..end step 500).flatMap { at -> listOf(
                SensorReading("AAOS.GEAR_SELECTION",at,ReadingKind.SNAPSHOT,state=if (id=="reverse") "REVERSE" else "DRIVE"),
                SensorReading("Vehicle.Brake.getCurrentBrakePedalStatus",at,ReadingKind.SNAPSHOT,flag=false),
                SensorReading("Vehicle.TurnSignal.getCurrentTurnSignalState",at,ReadingKind.SNAPSHOT,state=signals.first { it.atMs == at }.value!!.name)) })
    }
}
