package com.noonmaru.routy.evaluation

import kotlin.math.*

/** Straight-road simulation contract. d and heading are positive to the left; times are session milliseconds. */
data class EnvironmentVehicle(val id: String, val s: Double, val d: Double, val speed: Double, val length: Double = 4.8, val width: Double = 1.8)
data class EnvironmentFrame(val runId: String, val sequence: Long, val atMs: Long, val s: Double, val d: Double,
    val heading: Double, val speed: Double, val signal: String, val vehicles: List<EnvironmentVehicle>,
    val light: String, val stopLineS: Double, val available: Boolean = true)
data class EnvironmentEvent(val kind: String, val startMs: Long, val endMs: Long, val detail: String) {
    val feedback: String get() = when(kind) {
        "LANE_CHANGE_LEFT" -> "왼쪽 차로로 이동한 뒤 안정됐어요"
        "LANE_CHANGE_RIGHT" -> "오른쪽 차로로 이동한 뒤 안정됐어요"
        "LANE_CHANGE_RETURNED" -> "차로 경계를 넘었다가 원래 차로로 돌아왔어요"
        "INCOMPLETE_LANE_CHANGE" -> "관측이 끝나 차로 변경 완료 여부를 확인하지 못했어요"
        "LANE_BOUNDARY_APPROACH" -> "차로 경계에 가까운 상태가 이어졌어요. 좌우 여유를 확인해보세요"
        "SHORT_FOLLOWING_HEADWAY" -> "앞차와의 시간 간격이 짧았어요. 여유 있는 간격을 연습해보세요"
        "LOW_CLOSING_TTC" -> "앞차와의 간격이 빠르게 줄었어요"
        "STOPPED_BEFORE_RED" -> "적색 신호에서 정지선 전에 멈췄어요"
        "STOP_LINE_CROSSING_RED" -> "적색 신호 중 정지선 통과가 관측됐어요"
        "STOP_LINE_CROSSING_GREEN" -> "녹색 신호 중 정지선을 통과했어요"
        "STOP_LINE_CROSSING_YELLOW" -> "황색 신호 중 정지선을 통과했어요. 접근 상황을 복습해보세요"
        "SIGNAL_CROSSING_UNCERTAIN" -> "신호 전환과 통과 시각이 겹쳐 평가를 보류했어요"
        "MISSING_LANE_CHANGE_SIGNAL" -> "차로 변경 전 방향지시등 선택이 관측되지 않았어요"
        "LATE_LANE_CHANGE_SIGNAL" -> "차로 변경 전 방향지시등 준비 시간이 짧았어요"
        "OPPOSITE_LANE_CHANGE_SIGNAL" -> "이동 방향과 반대 방향지시등이 관측됐어요"
        "INTERRUPTED_LANE_CHANGE_SIGNAL" -> "차로 변경 중 방향지시등이 바뀌었어요"
        "LANE_CHANGE_SIGNAL_OBSERVED" -> "차로 변경 전 방향지시등 준비가 관측됐어요"
        else -> "방향지시등 관측이 부족해 사용 평가를 보류했어요"
    }
}
data class EnvironmentResult(val source: String, val frames: Int, val excluded: Int, val events: List<EnvironmentEvent>)

/** Experimental learning feedback, not a legal or safety certification. No network or renderer dependency. */
object EnvironmentEvaluation {
    fun usable(f: EnvironmentFrame): Boolean = f.available && f.atMs>=0 && f.sequence>=0 && f.runId.isNotBlank() &&
        listOf(f.s,f.d,f.heading,f.speed,f.stopLineS).all { it.isFinite() } && f.speed in 0.0..60.0 && abs(f.heading)<PI/2 &&
        round(f.d/3.7).toInt() in -1..1 &&
        f.signal in setOf("OFF","LEFT","RIGHT","UNKNOWN") && f.light in setOf("RED","YELLOW","GREEN") &&
        f.vehicles.map { it.id }.distinct().size==f.vehicles.size &&
        f.vehicles.all { v -> v.id.isNotBlank() && listOf(v.s,v.d,v.speed,v.length,v.width).all { it.isFinite() } && v.speed>=0 && v.length>0 && v.width>0 }
    fun decode(text: String): List<EnvironmentFrame> = JsonDocuments.read(text).objects("frames").map { f ->
        require(f.string("source") == "SIMULATED_ENVIRONMENT" && f.long("version") == 2L)
        EnvironmentFrame(f.string("runId"), f.long("sequence"), f.long("atMs"), f.number("s"), f.number("d"),
            f.number("heading"), f.number("speed"), f.string("signal"), f.objects("vehicles").map { v ->
                EnvironmentVehicle(v.string("id"),v.number("s"),v.number("d"),v.number("speed"),v.number("length"),v.number("width"))
            }, f.string("light"), f.number("stopLineS"), f.boolean("available"))
    }

    fun evaluate(frames: List<EnvironmentFrame>): EnvironmentResult {
        val events = mutableListOf<EnvironmentEvent>()
        var previous: EnvironmentFrame? = null
        var lane: Int? = null
        var target: Int? = null
        var settle = 0L
        var changeStart: Long? = null
        var originalSignal = "UNKNOWN"
        var signalSince: Long? = null
        var excluded = 0
        var leaderId: String? = null
        val episodes = mutableMapOf<String, Pair<Long, Long>>()
        fun finish(key: String) {
            episodes.remove(key)?.let { (start,end) -> if(end-start >= 500) events += EnvironmentEvent(key,start,end,"SIMULATED_ENVIRONMENT") }
        }
        fun episode(key: String, active: Boolean, at: Long) {
            if(active) episodes[key] = (episodes[key]?.first ?: at) to at else finish(key)
        }
        fun reset() {
            episodes.keys.toList().forEach(::finish)
            changeStart?.let { events += EnvironmentEvent("INCOMPLETE_LANE_CHANGE",it,previous?.atMs ?: it,"observation discontinuity") }
            lane=null; target=null; changeStart=null; signalSince=null; settle=0; leaderId=null
        }
        for(f in frames) {
            val p=previous
            val valid=usable(f)
            val continuous=p!=null && p.runId==f.runId && f.sequence>p.sequence && f.atMs-p.atMs in 1..250 && abs(f.s-p.s)<20 && abs(f.d-p.d)<2
            if(!valid) { excluded++; reset(); previous=null; continue }
            if(p!=null && !continuous) { excluded++; reset() }
            val currentLane=round(f.d/3.7).toInt()
            if(currentLane !in -1..1) { excluded++; reset(); previous=null; continue }
            val halfWidth=abs(cos(f.heading))*.9+abs(sin(f.heading))*2.4
            val inside=abs(f.d-currentLane*3.7)+halfWidth <= 1.85-.1
            if(lane==null && inside) lane=currentLane
            if(p==null || p.signal!=f.signal || !continuous) signalSince=f.atMs
            val base=lane
            if(base!=null && f.speed>1) {
                val crosses=abs(f.d-base*3.7)+halfWidth>1.85
                if(crosses && changeStart==null) { changeStart=f.atMs; originalSignal=f.signal; target=null }
                if(changeStart!=null) {
                    if(currentLane!=base && inside) {
                        if(target!=currentLane) { target=currentLane; settle=f.atMs }
                        if(f.atMs-settle>=1000) {
                            val direction=if(currentLane>base) "LEFT" else "RIGHT"
                            val start=changeStart!!
                            events += EnvironmentEvent("LANE_CHANGE_$direction",start,f.atMs,"$base -> $currentLane")
                            val usage=when {
                                originalSignal=="UNKNOWN" || f.signal=="UNKNOWN" -> "LANE_CHANGE_SIGNAL_UNAVAILABLE"
                                originalSignal=="OFF" -> "MISSING_LANE_CHANGE_SIGNAL"
                                originalSignal!=direction -> "OPPOSITE_LANE_CHANGE_SIGNAL"
                                f.signal!=direction -> "INTERRUPTED_LANE_CHANGE_SIGNAL"
                                (signalSince ?: start)>start-3000 -> "LATE_LANE_CHANGE_SIGNAL"
                                else -> "LANE_CHANGE_SIGNAL_OBSERVED"
                            }
                            events += EnvironmentEvent(usage,start,f.atMs,"experimental preparation interval 3000ms")
                            lane=currentLane; changeStart=null; target=null
                        }
                    } else {
                        target=null
                        if(currentLane==base && inside) {
                            events += EnvironmentEvent("LANE_CHANGE_RETURNED",changeStart!!,f.atMs,"boundary excursion followed by return")
                            changeStart=null
                        }
                    }
                }
                episode("LANE_BOUNDARY_APPROACH",changeStart==null && 1.85-abs(f.d-base*3.7)-halfWidth<.25,f.atMs)
            } else episode("LANE_BOUNDARY_APPROACH",false,f.atMs)
            val leader=f.vehicles.filter { it.s>f.s && abs(it.d-f.d)<(it.width+1.8)/2 }.minByOrNull { it.s }
            if(leader?.id!=leaderId) { finish("SHORT_FOLLOWING_HEADWAY"); finish("LOW_CLOSING_TTC"); leaderId=leader?.id }
            val gap=leader?.let { it.s-f.s-(it.length+4.8)/2 }
            val closing=leader?.let { f.speed-it.speed }
            episode("SHORT_FOLLOWING_HEADWAY",gap!=null && gap>0 && f.speed>1 && gap/f.speed<1.5,f.atMs)
            episode("LOW_CLOSING_TTC",gap!=null && gap>0 && closing!=null && closing>.1 && gap/closing<3,f.atMs)
            if(continuous && p.s+2.4 < f.stopLineS && f.s+2.4>=f.stopLineS && p.stopLineS==f.stopLineS) {
                val kind=if(p.light!=f.light) "SIGNAL_CROSSING_UNCERTAIN" else "STOP_LINE_CROSSING_${f.light}"
                events += EnvironmentEvent(kind,p.atMs,f.atMs,"front bumper crossing interval")
            }
            episode("STOPPED_BEFORE_RED",f.light=="RED" && f.speed<.1 && f.stopLineS-(f.s+2.4) in 0.0..5.0,f.atMs)
            previous=f
        }
        reset()
        return EnvironmentResult("SIMULATED_ENVIRONMENT",frames.size,excluded,events.sortedBy { it.startMs })
    }
}
