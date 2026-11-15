package org.eowoo.routy.evaluation

/** Frozen integration and feedback for environment-0.1/0.2 recordings. */
internal object LegacyEnvironmentIntegration {
    fun encode(rows: List<AlignedEnvironmentFrame>): List<JsonObject> = rows.map { row ->
        mapOf("sessionAtMs" to row.sessionAtMs,"uncertaintyMs" to row.uncertaintyMs,"serverAtMs" to row.serverAtMs,"frame" to encodeFrame(row.frame))
    }
    fun encodeFrame(f: EnvironmentFrame): JsonObject = mapOf("version" to 2,"source" to "SIMULATED_ENVIRONMENT",
        "runId" to f.runId,"sequence" to f.sequence,"atMs" to f.atMs,"s" to f.s,"d" to f.d,"heading" to f.heading,
        "speed" to f.speed,"signal" to f.signal,"light" to f.light,"stopLineS" to f.stopLineS,"available" to f.available,
        "vehicles" to f.vehicles.map { mapOf("id" to it.id,"s" to it.s,"d" to it.d,"speed" to it.speed,"length" to it.length,"width" to it.width) })
    fun decode(rows: List<JsonObject>): List<AlignedEnvironmentFrame> = rows.map { row ->
        AlignedEnvironmentFrame(EnvironmentEvaluation.decode(JsonDocuments.write(mapOf("frames" to listOf(row["frame"])))) .single(),row.long("sessionAtMs"),row.long("uncertaintyMs"),if(row["serverAtMs"]==null) null else row.long("serverAtMs"))
    }
    fun frames(session: DrivingSession): List<EnvironmentFrame> = session.environment.map { row ->
        val f=row.frame
        val speed=session.speeds.lastOrNull { it.atMs<=row.sessionAtMs && row.sessionAtMs-it.atMs<=500 }
        val synchronized= row.uncertaintyMs in 0..100 && row.sessionAtMs in session.startMs..session.endMs &&
            session.speeds.zipWithNext().all { (a,b) -> b.atMs>a.atMs } &&
            session.speedGaps.none { row.sessionAtMs in it.startMs until it.endMs } && speed?.available==true &&
            speed.value?.let { kotlin.math.abs(it-f.speed)<=2 } == true
        val sdkSignal=if(session.hazardStateKnown && session.signalSemantics==SignalSemantics.SELECTION_STATE)
            session.signals.lastOrNull { it.atMs<=row.sessionAtMs && row.sessionAtMs-it.atMs<=500 }?.takeIf { it.available }?.value?.name else null
        f.copy(atMs=row.sessionAtMs,available=EnvironmentEvaluation.usable(f) && synchronized,signal=sdkSignal?.takeIf { it in setOf("OFF","LEFT","RIGHT") } ?: "UNKNOWN")
    }
    fun assess(session: DrivingSession, frames: List<EnvironmentFrame>, result: EnvironmentResult): List<Assessment> {
        val intervals=frames.zipWithNext().filter { (a,b) ->
            a.available && b.available && a.runId==b.runId && b.sequence>a.sequence && b.atMs-a.atMs in 1..250 && kotlin.math.abs(b.s-a.s)<20 && kotlin.math.abs(b.d-a.d)<2
        }
        return listOf(Category.LANE_KEEPING,Category.TRAFFIC_SIGNAL_RESPONSE,Category.FOLLOWING_DISTANCE).map { category ->
            val eligible=intervals.sumOf { (a,b) ->
                if(category==Category.LANE_KEEPING && result.events.any { it.kind in setOf("LANE_CHANGE_LEFT","LANE_CHANGE_RIGHT","MULTI_LANE_CHANGE_LEFT","MULTI_LANE_CHANGE_RIGHT") && it.startMs<b.atMs && it.endMs>a.atMs }) 0L else b.atMs-a.atMs
            }.coerceAtMost(session.durationMs)
            val hasOpportunity=when(category) {
                Category.FOLLOWING_DISTANCE -> frames.any { f -> f.available && f.vehicles.any { it.s>f.s && kotlin.math.abs(it.d-f.d)<(it.width+1.8)/2 } }
                Category.TRAFFIC_SIGNAL_RESPONSE -> frames.any { it.available && it.stopLineS-it.s in 0.0..100.0 }
                else -> frames.any { it.available && it.speed>1 }
            }
            Assessment(category,when { eligible==0L -> AssessmentStatus.NOT_ASSESSED; eligible<session.durationMs -> AssessmentStatus.PARTIAL; !hasOpportunity -> AssessmentStatus.NO_OPPORTUNITY; else -> AssessmentStatus.ASSESSED },
                eligibleMs=eligible,excludedMs=session.durationMs-eligible,
                reasons=if(eligible<session.durationMs) setOf(ExclusionReason.ENVIRONMENT_DATA_REQUIRED) else emptySet())
        }
    }
    fun feedback(result: EnvironmentResult): List<LearningFeedback> = result.events.mapIndexed { index,event ->
        LearningFeedback("environment-$index",event.startMs,event.endMs,ReviewPriority.REVIEW,event.feedback,
            "가상 환경에서 관측한 결과입니다.","같은 시나리오를 다시 연습하며 주변 여유와 신호를 확인해보세요.",
            if(event.kind.contains("UNAVAILABLE") || event.kind.contains("UNCERTAIN") || event.kind.contains("INCOMPLETE")) EvidenceStrength.LIMITED else EvidenceStrength.SUPPORTED,
            mapOf("source" to result.source,"event" to event.kind,"detail" to event.detail),emptySet())
    }
}
