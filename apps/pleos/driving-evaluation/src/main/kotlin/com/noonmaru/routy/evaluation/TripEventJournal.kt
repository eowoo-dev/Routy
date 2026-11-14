package com.noonmaru.routy.evaluation

/** One pending episode per rule, bounded preview, and an append-only full event ledger. */
class TripEventJournal(private val emit:(EvaluationEvent)->Unit) {
    private val pending=linkedMapOf<EventKind,EvaluationEvent>()
    private val preview=mutableListOf<EvaluationEvent>()
    val counts=linkedMapOf<EventKind,Long>()
    private fun publish(event:EvaluationEvent) {
        counts[event.kind]=(counts[event.kind] ?: 0)+1
        if(preview.size<WindowReportSummary.PREVIEW)preview+=event
        emit(event)
    }
    fun accept(events:List<EvaluationEvent>,watermark:Long,final:Boolean=false):List<EvaluationEvent> {
        val emitted=mutableListOf<EvaluationEvent>()
        fun flush(kind:EventKind){pending.remove(kind)?.let{publish(it);emitted+=it}}
        for(event in events.sortedBy{it.startMs}) {
            val old=pending[event.kind]
            val sameContext=old!=null && listOf("roadId","speedLimitMps","turnId").all{old.evidence[it]==event.evidence[it]}
            if(old!=null && sameContext && event.startMs<=old.endMs && event.endMs>=old.startMs) {
                val evidence=(old.evidence.keys+event.evidence.keys).associateWith { key ->
                    val a=old.evidence[key];val b=event.evidence[key]
                    if(key.startsWith("peak") && a?.toDoubleOrNull()!=null && b?.toDoubleOrNull()!=null)
                        maxOf(a.toDouble(),b.toDouble()).toString() else b ?: a!!
                }
                pending[event.kind]=old.copy(startMs=minOf(old.startMs,event.startMs),endMs=maxOf(old.endMs,event.endMs),evidence=evidence)
            } else {flush(event.kind);pending[event.kind]=event}
        }
        pending.keys.toList().filter{final || pending.getValue(it).endMs<watermark}.forEach(::flush)
        return emitted
    }
    fun overview()=preview.sortedWith(compareBy<EvaluationEvent>{it.startMs}.thenBy{it.kind.ordinal})
}
