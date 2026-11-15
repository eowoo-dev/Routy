package org.eowoo.routy.evaluation

/** Synthetic observations stay explicitly synthetic; they never impersonate SDK speed samples. */
object ScenarioReview {
    fun session(frames:List<EnvironmentFrame>):DrivingSession {
        require(frames.size>=2 && frames.map { it.runId }.distinct().size==1) { "A single scenario run with at least two frames is required" }
        require(frames.last().atMs>frames.first().atMs)
        return DrivingSession(frames.first().atMs,frames.last().atMs,DataOrigin.SYNTHETIC,
            speeds=frames.map { Observation(it.atMs,it.speed,it.available) },
            environment=frames.map { AlignedEnvironmentFrame(it,it.atMs,0) })
    }
    fun recording(frames:List<EnvironmentFrame>,report:EvaluationReport):JsonObject {
        require(report.origin==DataOrigin.SYNTHETIC && report.engineVersion=="0.7.0")
        return mapOf("schemaVersion" to 1,"engineVersion" to report.engineVersion,"ruleVersion" to report.config.ruleVersion,
            "environmentRuleVersion" to "environment-0.3","origin" to DataOrigin.SYNTHETIC.name,
            "configSignature" to report.config.toString(),"practiceConfigSignature" to report.practiceConfig.toString(),
            "frames" to frames.map(EnvironmentIntegration::encodeFrame),"analysis" to TraceReplay.summary(report),
            "contextFindings" to TraceReplay.summary(report)["contextFindings"])
    }
}
