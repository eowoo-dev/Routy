package com.noonmaru.routy.evaluation

import kotlin.math.*

enum class EnvironmentReason(val label: String) {
    NO_OBSERVATION("환경 관측 없음"), CLOCK_UNCERTAIN("시각 대응 오차가 큼"), SDK_MISSING("유효한 SDK 속도 없음"),
    SDK_MISMATCH("SDK와 환경 속도 불일치"), SDK_GAP("SDK 수집 단절"), INVALID_SAMPLE("잘못된 환경 값"),
    OUT_OF_SCOPE("지원하는 도로 범위 밖"), UNAVAILABLE("환경 관측 불가"), DUPLICATE("중복 또는 역순 프레임"),
    TIME_GAP("환경 관측 시간 단절"), RUN_CHANGED("실행 구간 변경"), POSITION_JUMP("위치가 불연속적으로 이동"),
    SIGNAL_UNKNOWN("방향지시등 의미 또는 상태 미확인"), INTENTIONAL_CHANGE("완료된 차로 변경 구간")
}
data class EnvironmentIssue(val startMs: Long,val endMs: Long,val reason: EnvironmentReason)
data class EnvironmentCoverage(val channel: String,val totalMs: Long,val eligibleMs: Long,val noOpportunityMs: Long,
    val excludedMs: Long,val reasons: Map<String,Long>)
data class EnvironmentReviewGroup(val id: String,val startMs: Long,val endMs: Long,val title: String,val nextPractice: String,
    val kinds: List<String>,val evidence: Map<String,Double>,val limited: Boolean)
data class EnvironmentReview(val quality: List<EnvironmentCoverage>,val issues: List<EnvironmentIssue>,
    val metrics: Map<String,Double>,val groups: List<EnvironmentReviewGroup>)

object EnvironmentReviewBuilder {
    private fun leader(f: EnvironmentFrame)=f.vehicles.filter { it.s>f.s && abs(it.d-f.d)<(it.width+1.8)/2 }.minByOrNull { it.s }
    private fun completed(e:EnvironmentEvent)=e.kind in setOf("LANE_CHANGE_LEFT","LANE_CHANGE_RIGHT","MULTI_LANE_CHANGE_LEFT","MULTI_LANE_CHANGE_RIGHT")
    private fun samples(frames: List<EnvironmentFrame>,changes:List<EnvironmentEvent> = emptyList()): Map<String,Double> {
        val values=mutableMapOf<String,Double>()
        fun minValue(key:String,value:Double) { if(value.isFinite()) values[key]=min(values[key] ?: value,value) }
        for(f in frames.filter { EnvironmentEvaluation.usable(it) }) {
            val half=abs(cos(f.heading))*.9+abs(sin(f.heading))*2.4
            if(changes.none { completed(it) && f.atMs in it.startMs..it.endMs })
                minValue("minLaneClearanceM",1.85-abs(f.d-round(f.d/3.7)*3.7)-half)
            val v=leader(f)
            if(v!=null) {
                val gap=v.s-f.s-(v.length+4.8)/2
                minValue("minGapM",gap)
                if(gap>0 && f.speed>1)minValue("minHeadwayS",gap/f.speed)
                if(gap>0 && f.speed-v.speed>.1)minValue("minTtcS",gap/(f.speed-v.speed))
            }
            if(f.light=="RED" && f.speed<.1) minValue("redStopLineGapM",f.stopLineS-f.s-2.4)
        }
        return values.toSortedMap()
    }
    fun enrich(frames:List<EnvironmentFrame>,events:List<EnvironmentEvent>): List<EnvironmentEvent> = events.map { event ->
        val rows=frames.filter { it.atMs in event.startMs..event.endMs }
        val metrics=samples(rows,events).toMutableMap()
        metrics["durationMs"]=(event.endMs-event.startMs).coerceAtLeast(0).toDouble()
        if(event.kind.contains("SIGNAL") && !event.kind.startsWith("STOP_")) {
            val change=events.firstOrNull { it.startMs==event.startMs && (it.kind=="LANE_CHANGE_LEFT" || it.kind=="LANE_CHANGE_RIGHT" || it.kind.startsWith("MULTI_LANE_CHANGE")) }
            if(change!=null) {
                val direction=if(change.kind.endsWith("LEFT")) "LEFT" else "RIGHT"
                val prior=frames.filter { it.atMs<=event.startMs }.asReversed()
                var since=event.startMs
                var next=event.startMs
                var sequence=Long.MAX_VALUE
                val run=rows.firstOrNull()?.runId
                for(f in prior) { if(!EnvironmentEvaluation.usable(f) || f.runId!=run || f.sequence>=sequence || f.signal!=direction || next-f.atMs !in 0..250)break;since=f.atMs;next=f.atMs;sequence=f.sequence }
                metrics["signalPreparationMs"]=(event.startMs-since).toDouble()
                metrics["signalOffDurationMs"]=rows.zipWithNext().sumOf { (a,b)->if(a.signal=="OFF" && b.atMs-a.atMs in 1..250) b.atMs-a.atMs else 0L }.toDouble()
            }
        }
        event.copy(evidence=metrics.toSortedMap(),subjectId=rows.asReversed().firstNotNullOfOrNull { leader(it)?.id })
    }
    fun build(frames:List<EnvironmentFrame>,events:List<EnvironmentEvent>,extra:List<EnvironmentIssue> = emptyList(),
        startMs:Long=frames.minOfOrNull { it.atMs.coerceAtLeast(0) } ?: 0,endMs:Long=frames.maxOfOrNull { it.atMs.coerceAtLeast(startMs) } ?: startMs):EnvironmentReview {
        require(startMs>=0 && endMs>=startMs)
        val issues=extra.toMutableList()
        val orderIssues=frames.zipWithNext().mapNotNull { (a,b) ->
            if(b.atMs<=a.atMs || (a.runId==b.runId && b.sequence<=a.sequence))
                EnvironmentIssue(min(a.atMs,b.atMs),max(a.atMs,b.atMs),EnvironmentReason.DUPLICATE) else null
        }
        issues+=orderIssues
        val sorted=frames.filter { it.atMs in startMs..endMs }.sortedBy { it.atMs }
        val cuts=(listOf(startMs,endMs)+sorted.map { it.atMs }+(extra+orderIssues).flatMap { listOf(it.startMs.coerceIn(startMs,endMs),it.endMs.coerceIn(startMs,endMs)) }).distinct().sorted()
        val channels=listOf("LANE_KEEPING","TRAFFIC_SIGNAL_RESPONSE","FOLLOWING_DISTANCE","LANE_CHANGE_SIGNAL")
        val quality=channels.map { channel ->
            var eligible=0L;var noOpportunity=0L;val reasons=linkedMapOf<String,Long>()
            for((a,b) in cuts.zipWithNext()) {
                if(b<=a)continue
                val f=sorted.lastOrNull { it.atMs<=a }
                val next=sorted.firstOrNull { it.atMs>=b }
                var reason=(extra+orderIssues).firstOrNull { (it.startMs<=a && a<it.endMs) || (it.startMs==a && it.endMs==a) }?.reason
                if(reason==null) reason=when {
                    f==null -> EnvironmentReason.NO_OBSERVATION
                    !f.available -> EnvironmentReason.UNAVAILABLE
                    next!=null && !next.available -> EnvironmentReason.UNAVAILABLE
                    !EnvironmentEvaluation.usable(f) -> if(f.d.isFinite() && abs(f.d)>5.55) EnvironmentReason.OUT_OF_SCOPE else EnvironmentReason.INVALID_SAMPLE
                    b-a>250 || a-f.atMs>250 -> EnvironmentReason.TIME_GAP
                    next!=null && next.runId!=f.runId -> EnvironmentReason.RUN_CHANGED
                    next!=null && next.atMs>f.atMs && next.sequence<=f.sequence -> EnvironmentReason.DUPLICATE
                    next!=null && (abs(next.s-f.s)>=20 || abs(next.d-f.d)>=2) -> EnvironmentReason.POSITION_JUMP
                    next!=null && !EnvironmentEvaluation.usable(next) -> EnvironmentReason.INVALID_SAMPLE
                    channel=="LANE_KEEPING" && events.any { completed(it) && it.startMs<b && it.endMs>a } -> EnvironmentReason.INTENTIONAL_CHANGE
                    channel=="LANE_CHANGE_SIGNAL" && f.signal=="UNKNOWN" && events.any { it.kind.contains("LANE_CHANGE") && !it.kind.contains("SIGNAL") && it.startMs<b && it.endMs>a } -> EnvironmentReason.SIGNAL_UNKNOWN
                    else -> null
                }
                if(reason!=null) {
                    reasons[reason.name]=(reasons[reason.name] ?: 0)+(b-a)
                    if(channel==channels.first())issues+=EnvironmentIssue(a,b,reason)
                } else {
                    val opportunity=when(channel) {
                        "LANE_KEEPING" -> f!!.speed>1
                        "TRAFFIC_SIGNAL_RESPONSE" -> f!!.stopLineS-f.s-2.4 in 0.0..100.0
                        "FOLLOWING_DISTANCE" -> leader(f!!)!=null
                        else -> events.any { it.kind.contains("LANE_CHANGE") && !it.kind.contains("SIGNAL") && it.startMs<b && it.endMs>a }
                    }
                    if(opportunity)eligible+=b-a else noOpportunity+=b-a
                }
            }
            val total=(endMs-startMs).coerceAtLeast(0)
            EnvironmentCoverage(channel,total,eligible,noOpportunity,(total-eligible-noOpportunity).coerceAtLeast(0),reasons)
        }
        val grouped=mutableListOf<MutableList<EnvironmentEvent>>()
        fun family(e:EnvironmentEvent)=when {
            e.kind.contains("LANE") -> "LANE"
            e.kind.contains("FOLLOWING") || e.kind.contains("TTC") || e.kind.contains("VEHICLE") -> "DISTANCE:${e.subjectId}"
            else -> "TRAFFIC"
        }
        for(e in events.sortedBy { it.startMs }) {
            val group=grouped.lastOrNull { family(it.first())==family(e) && e.startMs<=it.maxOf { row->row.endMs }+1000 &&
                it.none { row -> row.kind.contains("LANE_CHANGE") && e.kind.contains("LANE_CHANGE") && row.startMs!=e.startMs } }
            if(group==null)grouped+=mutableListOf(e) else group+=e
        }
        val groups=grouped.mapIndexed { index,rows ->
            val representative=rows.firstOrNull { it.kind.contains("INTERRUPTED") || it.kind.contains("MISSING") || it.kind.contains("OPPOSITE") || it.kind.contains("OVERLAP") || it.kind=="STOP_LINE_CROSSING_RED" }
                ?: rows.firstOrNull { it.kind.contains("UNKNOWN") || it.kind.contains("UNAVAILABLE") || it.kind.contains("UNCERTAIN") || it.kind.contains("INCOMPLETE") } ?: rows.first()
            val family=family(representative)
            val advice=when {
                rows.any { it.kind=="LANE_CHANGE_SIGNAL_UNAVAILABLE" } -> "방향지시등 입력이 전체 이동 구간에 연결되는지 먼저 확인한 뒤 다시 연습해보세요."
                rows.any { it.kind=="OPPOSITE_LANE_CHANGE_SIGNAL" } -> "이동할 방향과 방향지시등 선택이 일치하는지 출발 전에 확인해보세요."
                rows.any { it.kind=="INTERRUPTED_LANE_CHANGE_SIGNAL" } -> "변경 도중 해제한 시점을 복습하고, 새 차로에서 안정될 때까지 방향지시등을 유지해보세요."
                rows.any { it.kind.contains("SIGNAL") && it.kind.contains("LANE") } -> "이동 전 방향지시등을 준비하고 변경을 마칠 때까지 선택 상태를 유지해보세요."
                rows.any { it.kind=="LANE_BOUNDARY_INTRUSION" || it.kind=="LANE_BOUNDARY_APPROACH" || it.kind=="LANE_CHANGE_RETURNED" } -> "차로 중심을 기준으로 좌우 차체 여유를 확인하며 작은 조작으로 위치를 안정시켜보세요."
                family=="LANE" -> "한 차로씩 이동한 뒤 차로 안의 위치와 좌우 여유가 안정되는지 확인해보세요."
                family.startsWith("DISTANCE") && rows.any { it.kind=="LEAD_VEHICLE_CUT_IN" } -> "끼어든 차량의 위치를 확인하고 여유 간격을 회복하는 과정을 연습해보세요."
                family.startsWith("DISTANCE") -> "앞차 속도에 맞춰 접근 속도를 줄이고 충분한 시간 간격을 확보해보세요."
                else -> "접근 중 신호를 확인하고 정지선 앞에서 여유 있게 감속·정차하는 과정을 연습해보세요."
            }
            val evidence=linkedMapOf<String,Double>()
            for(e in rows) for((k,v) in e.evidence) evidence[k]=if(k.startsWith("min")) min(evidence[k] ?: v,v) else max(evidence[k] ?: v,v)
            EnvironmentReviewGroup("environment-${frames.firstOrNull()?.runId ?: "empty"}-$index",rows.minOf { it.startMs },rows.maxOf { it.endMs },representative.feedback,advice,
                rows.map { it.kind }.distinct(),evidence,rows.any { it.kind.contains("UNAVAILABLE") || it.kind.contains("UNCERTAIN") || it.kind.contains("INCOMPLETE") })
        }.sortedBy { it.startMs }
        return EnvironmentReview(quality,issues.distinct().sortedBy { it.startMs },samples(frames,events),groups)
    }
}
