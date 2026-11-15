package org.eowoo.routy.evaluation

import kotlin.math.*

/** Frozen environment-0.1 behavior for captured engine 0.5.0 records. */
internal object LegacyEnvironmentEvaluation {
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
