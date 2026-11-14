package com.noonmaru.routy.evaluation

/** Explicitly synthetic fixture, shared by the command-line replay and developer screen. */
object ExampleDrive {
    fun session(): DrivingSession {
        val times = (0L..12_000L step 100).toList()
        return DrivingSession(0, 12_000, DataOrigin.SYNTHETIC,
            speeds = times.map { ms ->
                val t = ms / 1_000.0
                val speed = when {
                    t < 1 -> 10.0
                    t < 4 -> 10.0 + 3.0 * (t - 1)
                    t < 7 -> 19.0
                    t < 9 -> 19.0 - 4.0 * (t - 7)
                    else -> 11.0
                }
                Observation(ms, speed)
            },
            roads = times.filter { it % 1_000L == 0L }.map { Observation(it, RoadContext("demo-road", "demo-road", 50.0 / 3.6, 12_000)) },
            signals = times.filter { it % 500L == 0L }.map { Observation(it, SignalSelection.OFF) },
            signalSemantics = SignalSemantics.SELECTION_STATE,
            turns = listOf(TurnManeuver("demo-left-turn", 9_000, 11_000, TurnDirection.LEFT,
                setOf(TurnEvidence.MATCHED_ROAD_TRANSITION, TurnEvidence.OBSERVED_HEADING_CHANGE))),
            turnDetectionComplete = true,
            steeringAngles = times.map { ms -> Observation(ms,
                if (ms <= 2_000) ms * 0.2 else 400.0 + 20.0 * kotlin.math.sin((ms - 2_000) / 1_000.0 * 2.0 * Math.PI)) },
            steeringTimeBasis = SteeringTimeBasis.PROVIDER,
        )
    }
}

fun main(args: Array<String>) {
    if("--environment-suite" in args) {
        val path=args[args.indexOf("--environment-suite")+1]
        val suite=JsonDocuments.read(java.io.File(path).readText())
        val results=suite.objects("cases").map { scenario ->
            val frames=EnvironmentEvaluation.decode(JsonDocuments.write(mapOf("frames" to scenario["frames"])))
            val report=EnvironmentEvaluation.evaluate(frames)
            val kinds=report.events.map { it.kind }.toSet()
            val errors=mutableListOf<String>()
            scenario.strings("expected").filter { it !in kinds }.forEach { errors+="Missing event: $it" }
            scenario.strings("forbidden").filter { it in kinds }.forEach { errors+="Unexpected event: $it" }
            if(report.excluded!=0) errors+="Excluded frames: ${report.excluded}"
            if(EnvironmentEvaluation.evaluate(frames)!=report) errors+="Replay is not deterministic"
            val analysis=DrivingEvaluationEngine().evaluate(ScenarioReview.session(frames))
            val recorded=JsonDocuments.read(JsonDocuments.write(ScenarioReview.recording(frames,analysis)))
            TraceReplay.verifyCaptured(recorded,DrivingEvaluationEngine().evaluate(TraceReplay.session(recorded)))
            if(analysis.environment!=report)errors+="App scenario analysis differs from direct evaluation"
            analysis.environment!!.review!!.quality.forEach { q ->
                if(q.eligibleMs+q.noOpportunityMs+q.excludedMs!=q.totalMs)errors+="Coverage partition differs: ${q.channel}"
            }
            scenario.objects("checks").filter { !it.boolean("passed") }.forEach { errors+="Geometry check failed: ${it.string("label")}" }
            println("${if(errors.isEmpty()) "PASS" else "FAIL"} ${scenario.string("id")}: $errors")
            mapOf("id" to scenario.string("id"),"passed" to errors.isEmpty(),"errors" to errors,
                "frames" to frames.size,"excluded" to report.excluded,"events" to report.events.map { mapOf("kind" to it.kind,"startMs" to it.startMs,"endMs" to it.endMs) },"analysis" to TraceReplay.summary(analysis),"recording" to recorded)
        }
        if("--output" in args) java.io.File(args[args.indexOf("--output")+1]).also { it.parentFile?.mkdirs() }
            .writeText(JsonDocuments.write(mapOf("suite" to suite.string("suite"),"results" to results)))
        check(results.isNotEmpty() && results.all { it["passed"]==true }) { "Environment scenario expectations failed" }
        return
    }
    if ("--environment" in args) {
        val index=args.indexOf("--environment")
        require(index+1<args.size)
        val frames=EnvironmentEvaluation.decode(java.io.File(args[index+1]).readText())
        val report=EnvironmentEvaluation.evaluate(frames)
        val output=EnvironmentReviewCodec.summary(report)
        val json=JsonDocuments.write(output)
        println(json)
        if("--output" in args) java.io.File(args[args.indexOf("--output")+1]).also { it.parentFile?.mkdirs() }.writeText(json)
        return
    }
    fun option(name: String): String? = args.indexOf(name).takeIf { it >= 0 }?.let { index ->
        require(index + 1 < args.size) { "Missing value for $name" }; args[index + 1]
    }
    fun write(value: Any) {
        option("--output")?.let { path -> java.io.File(path).also { it.parentFile?.mkdirs() }.writeText(JsonDocuments.write(value)) }
    }
    if ("--scenarios" in args) {
        val results = DemoScenarios.cases().map { scenario ->
            val report = DrivingEvaluationEngine().evaluate(scenario.session())
            val violations = scenario.violations(report)
            println("${if (violations.isEmpty()) "PASS" else "FAIL"} ${scenario.id}: $violations")
            mapOf("id" to scenario.id, "passed" to violations.isEmpty(), "violations" to violations, "report" to TraceReplay.summary(report))
        } + PracticeScenarios.cases().map { scenario ->
            val report = DrivingEvaluationEngine().evaluate(PracticeScenarios.session(scenario.id))
            val violations = scenario.violations(report)
            println("${if (violations.isEmpty()) "PASS" else "FAIL"} practice/${scenario.id}: $violations")
            mapOf("id" to "practice/${scenario.id}", "passed" to violations.isEmpty(), "violations" to violations,"report" to TraceReplay.summary(report))
        }
        write(mapOf("suite" to "demo-v1+practice-v1", "results" to results))
        check(results.all { it["passed"] == true }) { "Scenario expectation failed" }
        return
    }
    option("--trace")?.let { path ->
        val document = TraceReplay.read(java.io.File(path))
        val report = DrivingEvaluationEngine().evaluate(TraceReplay.session(document))
        if ("--verify-captured" in args) TraceReplay.verifyCaptured(document, report)
        write(TraceReplay.summary(report))
        println("RECORDED REPLAY engine=${report.engineVersion} readings=${report.telemetry.readings} contexts=${report.contextFindings.size}")
        return
    }
    require(args.isEmpty()) { "Use --scenarios [--output FILE] or --trace FILE [--verify-captured] [--output FILE]" }
    val report = DrivingEvaluationEngine().evaluate(ExampleDrive.session())
    println("SYNTHETIC REPLAY — NOT A REAL DRIVE OR VALIDATED DRIVING SCORE")
    println("engine=${report.engineVersion}, rules=${report.config.ruleVersion}")
    report.assessments.forEach { println("${it.category}: ${it.status}, eligibleMs=${it.eligibleMs}, excludedMs=${it.excludedMs}, reasons=${it.reasons}") }
    report.events.forEach { println("REVIEW ${it.kind} [${it.startMs}, ${it.endMs}]: ${it.evidence}") }
}
