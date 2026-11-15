package org.eowoo.routy.evaluation

data class SubmissionScenario(val id:String,val title:String,val description:String,val durationMs:Long,
    val frames:List<EnvironmentFrame>,val expected:List<String>,val forbidden:List<String>)

/** Offline demo observations use the same simulation as Virtual Console. Never live telemetry. */
object SubmissionScenarios {
    val all:List<SubmissionScenario> by lazy {
        val text=checkNotNull(javaClass.getResourceAsStream("/scenarios/submission-v1.json")) {
            "제출용 합성 입력이 APK에 없습니다"
        }.bufferedReader().use { it.readText() }
        val document=JsonDocuments.read(text)
        require(document["origin"]=="SYNTHETIC" && document["suite"]=="submission-v1")
        document.objects("cases").map { c ->
            val frames=EnvironmentEvaluation.decode(JsonDocuments.write(mapOf("frames" to c["frames"])))
            require(frames.isNotEmpty() && frames.all(EnvironmentEvaluation::usable))
            require(frames.withIndex().all { (i,f)->f.sequence==i+1L && f.atMs==(i+1L)*50 })
            require(frames.last().atMs==c.long("durationMs"))
            SubmissionScenario(c.string("id"),c.string("title"),c.string("description"),c.long("durationMs"),
                frames,c.strings("expected"),c.strings("forbidden"))
        }.also { require(it.map { s->s.id }.distinct().size==it.size) }
    }
}

/** Pausing or resetting never completes/evaluates a partial run. One step is 50ms of synthetic time. */
class SubmissionPlayback(val scenario:SubmissionScenario,val runId:String) {
    enum class Phase { READY,RUNNING,PAUSED,COMPLETED }
    var phase=Phase.READY; private set
    var index=-1; private set
    val elapsedMs:Long get()=if(index<0)0 else scenario.frames[index].atMs
    val frame:EnvironmentFrame get()=scenario.frames[index.coerceAtLeast(0)].copy(runId=runId)
    fun start(){check(phase==Phase.READY || phase==Phase.PAUSED);phase=Phase.RUNNING}
    fun pause(){if(phase==Phase.RUNNING)phase=Phase.PAUSED}
    fun step(){
        if(phase!=Phase.RUNNING)return
        index++
        if(index==scenario.frames.lastIndex)phase=Phase.COMPLETED
    }
    fun completedFrames():List<EnvironmentFrame> {
        check(phase==Phase.COMPLETED) { "완료 전에는 합성 평가를 저장하지 않습니다" }
        return scenario.frames.map { it.copy(runId=runId) }
    }
}
