package com.noonmaru.routy.evaluation

/** Server monotonic clock to the app session clock. The whole RTT bounds timestamp uncertainty. */
data class EnvironmentClockMapping(val offsetMs: Long, val uncertaintyMs: Long) {
    companion object {
        fun probe(sentAtMs: Long, receivedAtMs: Long, serverAtMs: Long): EnvironmentClockMapping {
            require(sentAtMs>=0 && receivedAtMs>=sentAtMs && serverAtMs>=0)
            return EnvironmentClockMapping(sentAtMs+(receivedAtMs-sentAtMs)/2-serverAtMs,receivedAtMs-sentAtMs)
        }
    }
}
data class AlignedEnvironmentFrame(val frame: EnvironmentFrame, val sessionAtMs: Long, val uncertaintyMs: Long, val serverAtMs: Long? = null)

object EnvironmentIntegration {
    fun prepare(session:DrivingSession):Pair<List<EnvironmentFrame>,List<EnvironmentIssue>> {
        val issues=mutableListOf<EnvironmentIssue>()
        val rows=session.environment
        val invalidSdkOrder=session.speeds.zipWithNext().any { (a,b)->b.atMs<=a.atMs }
        val frames=rows.mapIndexed { index,row ->
            val f=row.frame
            val speed=session.speeds.lastOrNull { it.atMs<=row.sessionAtMs }
            val previous=rows.getOrNull(index-1)
            val reason=when {
                row.sessionAtMs !in session.startMs..session.endMs -> EnvironmentReason.INVALID_SAMPLE
                row.uncertaintyMs !in 0..100 -> EnvironmentReason.CLOCK_UNCERTAIN
                !f.available -> EnvironmentReason.UNAVAILABLE
                absScope(f) -> EnvironmentReason.OUT_OF_SCOPE
                !EnvironmentEvaluation.usable(f) -> EnvironmentReason.INVALID_SAMPLE
                previous!=null && (row.sessionAtMs<=previous.sessionAtMs || (f.runId==previous.frame.runId && f.sequence<=previous.frame.sequence)) -> EnvironmentReason.DUPLICATE
                session.origin!=DataOrigin.SYNTHETIC && invalidSdkOrder -> EnvironmentReason.DUPLICATE
                session.origin!=DataOrigin.SYNTHETIC && session.speedGaps.any { row.sessionAtMs in it.startMs until it.endMs } -> EnvironmentReason.SDK_GAP
                session.origin!=DataOrigin.SYNTHETIC && (speed?.available!=true || speed.value==null || row.sessionAtMs-speed.atMs !in 0..500 || !speed.value.isFinite()) -> EnvironmentReason.SDK_MISSING
                session.origin!=DataOrigin.SYNTHETIC && kotlin.math.abs(speed!!.value!!-f.speed)>2 -> EnvironmentReason.SDK_MISMATCH
                else -> null
            }
            if(reason!=null)issues+=EnvironmentIssue(row.sessionAtMs.coerceIn(session.startMs,session.endMs),
                (rows.getOrNull(index+1)?.sessionAtMs ?: session.endMs).coerceIn(row.sessionAtMs.coerceIn(session.startMs,session.endMs),session.endMs),reason)
            val selection=if(session.origin==DataOrigin.SYNTHETIC) f.signal else if(session.hazardStateKnown && session.signalSemantics==SignalSemantics.SELECTION_STATE)
                session.signals.lastOrNull { it.atMs<=row.sessionAtMs && row.sessionAtMs-it.atMs<=500 }?.takeIf { it.available }?.value?.name ?: "UNKNOWN" else "UNKNOWN"
            f.copy(atMs=row.sessionAtMs,available=reason==null,signal=selection.takeIf { it in setOf("OFF","LEFT","RIGHT") } ?: "UNKNOWN")
        }
        return frames to issues
    }
    private fun absScope(f:EnvironmentFrame)=f.d.isFinite() && (kotlin.math.abs(f.d)>5.55 || kotlin.math.abs(f.heading)>=Math.PI/2)
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
    fun frames(session:DrivingSession):List<EnvironmentFrame> = prepare(session).first
    fun assess(session: DrivingSession, frames: List<EnvironmentFrame>, result: EnvironmentResult): List<Assessment> {
        result.review?.let { review -> return listOf(Category.LANE_KEEPING,Category.TRAFFIC_SIGNAL_RESPONSE,Category.FOLLOWING_DISTANCE).map { category ->
            val q=review.quality.first { it.channel==category.name }
            Assessment(category,when { q.eligibleMs>0 && q.excludedMs==0L -> AssessmentStatus.ASSESSED; q.eligibleMs>0 -> AssessmentStatus.PARTIAL; q.excludedMs==0L -> AssessmentStatus.NO_OPPORTUNITY; else -> AssessmentStatus.NOT_ASSESSED },
                eligibleMs=q.eligibleMs,excludedMs=q.excludedMs,reasons=q.reasons.keys.mapNotNull { runCatching { ExclusionReason.valueOf("ENV_"+it) }.getOrNull() }.toSet())
        } }
        return LegacyEnvironmentIntegration.assess(session,frames,result)
    }
    fun feedback(result:EnvironmentResult):List<LearningFeedback> = LegacyEnvironmentIntegration.feedback(result)
    fun groupedFeedback(result:EnvironmentResult):List<LearningFeedback> = result.review?.groups?.map { group ->
        LearningFeedback(group.id,group.startMs,group.endMs,if(group.limited) ReviewPriority.DATA_CHECK else ReviewPriority.REVIEW,group.title,
            "가상 환경 관측 · "+group.kinds.joinToString(),group.nextPractice,if(group.limited) EvidenceStrength.LIMITED else EvidenceStrength.SUPPORTED,
            group.evidence.mapValues { it.value.toString() }+mapOf("source" to result.source,"events" to group.kinds.joinToString()),
            if(group.limited) setOf("관측 또는 완료 여부 불확실") else emptySet())
    } ?: feedback(result)
}
