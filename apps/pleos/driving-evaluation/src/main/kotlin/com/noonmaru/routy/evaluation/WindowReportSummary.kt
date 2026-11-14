package com.noonmaru.routy.evaluation

/** Bounded overview. Complete per-window reports remain in the disk/upload archive. */
class WindowReportSummary {
    companion object {const val PREVIEW=32}
    private var value:EvaluationReport?=null
    private var windows=0
    private fun <T> preview(a:List<T>,b:List<T>)=(a+b.take(PREVIEW)).take(PREVIEW)
    private fun feedback(a:List<LearningFeedback>,b:List<LearningFeedback>):List<LearningFeedback> {
        val result=a.toMutableList()
        for(item in b) {
            val index=result.indexOfFirst{it.title==item.title && it.eventKinds==item.eventKinds &&
                it.evidence["context"]==item.evidence["context"] && it.startMs<=item.endMs && item.startMs<=it.endMs}
            if(index>=0) {
                val old=result[index]
                result[index]=old.copy(startMs=minOf(old.startMs,item.startMs),endMs=maxOf(old.endMs,item.endMs),missing=old.missing+item.missing)
            } else if(result.size<PREVIEW)result+=item
        }
        return result
    }
    private fun peak(a:Double?,b:Double?)=listOfNotNull(a,b).maxOrNull()
    private fun mean(a:Double?,an:Long,b:Double?,bn:Long):Double? {
        val count=(if(a==null)0 else an)+(if(b==null)0 else bn)
        return if(count==0L)null else ((a?:0.0)*an+(b?:0.0)*bn)/count
    }
    private fun bounded(r:EnvironmentReview?)=r?.copy(issues=r.issues.take(PREVIEW),groups=r.groups.take(PREVIEW))
    private fun merge(a:EnvironmentReview?,b:EnvironmentReview?):EnvironmentReview? {
        if(a==null)return bounded(b)
        if(b==null)return a
        val quality=(a.quality+b.quality).groupBy{it.channel}.map{(key,rows)->EnvironmentCoverage(key,
            rows.sumOf{it.totalMs},rows.sumOf{it.eligibleMs},rows.sumOf{it.noOpportunityMs},rows.sumOf{it.excludedMs},
            rows.flatMap{it.reasons.keys}.distinct().associateWith{k->rows.sumOf{it.reasons[k] ?: 0}})}
        val metrics=(a.metrics.keys+b.metrics.keys).associateWith { key ->
            val values=listOfNotNull(a.metrics[key],b.metrics[key]);if(key.startsWith("min") || key=="redStopLineGapM")values.min() else values.max()
        }
        return EnvironmentReview(quality,preview(a.issues,b.issues),metrics,preview(a.groups,b.groups))
    }
    fun add(next:EvaluationReport) {
        windows++
        val old=value
        if(old==null){value=next.copy(events=next.events.take(PREVIEW),feedback=next.feedback.take(PREVIEW),
            contextFindings=next.contextFindings.take(PREVIEW),practice=next.practice.copy(maneuvers=next.practice.maneuvers.take(PREVIEW),roadMatches=next.practice.roadMatches.take(PREVIEW),confirmedTurns=next.practice.confirmedTurns.take(PREVIEW)),
            environment=next.environment?.copy(events=next.environment.events.take(PREVIEW),review=bounded(next.environment.review)));return}
        require(old.endMs==next.startMs && old.origin==next.origin)
        val assessments=old.assessments.map { a ->
            val b=next.assessments.single{it.category==a.category}
            val eligible=a.eligibleMs+b.eligibleMs;val excluded=a.excludedMs+b.excludedMs
            a.copy(status=when {eligible>0 || a.evaluatedOpportunities+b.evaluatedOpportunities>0->AssessmentStatus.PARTIAL;a.status==AssessmentStatus.NO_OPPORTUNITY && b.status==a.status->a.status;else->AssessmentStatus.NOT_ASSESSED},
                eligibleMs=eligible,excludedMs=excluded,opportunities=a.opportunities+b.opportunities,evaluatedOpportunities=a.evaluatedOpportunities+b.evaluatedOpportunities,
                reasons=a.reasons+b.reasons+ExclusionReason.WINDOW_BOUNDARY)
        }
        val quality=(old.practice.quality+next.practice.quality).groupBy{it.channel}.map{(channel,rows)->
            ChannelQuality(channel,rows.sumOf{it.eligibleMs},rows.sumOf{it.totalMs},rows.sumOf{it.samples},rows.flatMap{it.reasons}.toSet()+"WINDOW_BOUNDARY")}
        value=old.copy(engineVersion=if(next.environment!=null || old.environment!=null)"0.7.0" else next.engineVersion,endMs=next.endMs,assessments=assessments,
            speed=SpeedMetrics(old.speed.eligibleMs+next.speed.eligibleMs,old.speed.excessiveMs+next.speed.excessiveMs,old.speed.eligibleDistanceM+next.speed.eligibleDistanceM,old.speed.excessiveDistanceM+next.speed.excessiveDistanceM,peak(old.speed.peakExcessMps,next.speed.peakExcessMps)),
            motion=MotionMetrics(old.motion.eligibleMs+next.motion.eligibleMs,mean(old.motion.meanAbsoluteAccelerationMps2,old.motion.eligibleMs,next.motion.meanAbsoluteAccelerationMps2,next.motion.eligibleMs),peak(old.motion.peakAccelerationMps2,next.motion.peakAccelerationMps2),peak(old.motion.peakDecelerationMps2,next.motion.peakDecelerationMps2)),
            steering=SteeringMetrics(old.steering.eligibleMs+next.steering.eligibleMs,mean(old.steering.meanAbsoluteRateDegPerSec,old.steering.eligibleMs,next.steering.meanAbsoluteRateDegPerSec,next.steering.eligibleMs),peak(old.steering.peakAbsoluteRateDegPerSec,next.steering.peakAbsoluteRateDegPerSec),old.steering.significantReversals+next.steering.significantReversals,old.steering.timeBasis),
            turns=TurnMetrics(old.turns.opportunities+next.turns.opportunities,old.turns.evaluated+next.turns.evaluated,old.turns.adequatelySignalled+next.turns.adequatelySignalled),
            events=preview(old.events,next.events),contextFindings=preview(old.contextFindings,next.contextFindings),feedback=feedback(old.feedback,next.feedback),
            telemetry=next.telemetry.copy(readings=Math.addExact(old.telemetry.readings,next.telemetry.readings),updates=Math.addExact(old.telemetry.updates,next.telemetry.updates),truncated=old.telemetry.truncated || next.telemetry.truncated),
            practice=PracticeAnalysis(preview(old.practice.maneuvers,next.practice.maneuvers),preview(old.practice.roadMatches,next.practice.roadMatches),quality,preview(old.practice.confirmedTurns,next.practice.confirmedTurns),false),
            environment=if(old.environment==null)next.environment?.copy(events=next.environment.events.take(PREVIEW),review=bounded(next.environment.review)) else old.environment.copy(frames=old.environment.frames+(next.environment?.frames?:0),excluded=old.environment.excluded+(next.environment?.excluded?:0),events=preview(old.environment.events,next.environment?.events?:emptyList()),review=merge(old.environment.review,next.environment?.review)))
    }
    fun report():EvaluationReport {
        val result=checkNotNull(value)
        if(windows==1)return result
        val notice=LearningFeedback("window-boundary",result.startMs,result.endMs,ReviewPriority.DATA_CHECK,
            "긴 주행의 평가 범위를 확인해 주세요","환경 행동의 상태는 구간을 넘어 이어집니다. 기본 규칙의 경계 메트릭은 일부 관측이며 요약은 상세 평가 일부를 보여줘요.",
            "전체 상세 평가에서 각 구간의 관측 품질을 함께 확인해 주세요.",EvidenceStrength.LIMITED,mapOf("windows" to windows.toString()),setOf("WINDOW_BOUNDARY"))
        return result.copy(feedback=listOf(notice)+result.feedback.take(PREVIEW-1))
    }
}
