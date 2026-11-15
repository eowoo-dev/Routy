package org.eowoo.routy.evaluation

/** Reject a trace replaced/reset while the app was downloading it. */
data class ScenarioCompletion(val status:String,val scenarioId:String,val runId:String,val count:Int,val atMs:Long) {
    companion object {
        fun decode(text:String):ScenarioCompletion {
            val value=JsonDocuments.read(text)
            @Suppress("UNCHECKED_CAST") val frame=value["frame"] as? JsonObject
            return ScenarioCompletion(value.string("status"),value["scenarioId"] as? String ?: "",frame?.string("runId") ?: "",
                value.long("count").also { require(it<=Int.MAX_VALUE) }.toInt(),frame?.long("atMs") ?: 0)
        }
        fun verify(before:ScenarioCompletion,after:ScenarioCompletion,frames:List<EnvironmentFrame>) {
            require(before.status=="completed" && before==after && before.runId.isNotBlank()) { "분석 중 시나리오가 변경되었습니다" }
            require(frames.size==before.count && frames.size>=2 && frames.all { it.runId==before.runId } && frames.last().atMs==before.atMs) { "완료 기록과 시나리오 상태가 다릅니다" }
        }
    }
}
