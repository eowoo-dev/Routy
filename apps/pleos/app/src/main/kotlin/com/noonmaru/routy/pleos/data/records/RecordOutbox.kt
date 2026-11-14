package com.noonmaru.routy.pleos.data.records

import com.noonmaru.routy.evaluation.*
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

/** Durable immutable payloads. Receipt state is written before anything can be considered sent. */
class RecordOutbox(private val root:File) {
    companion object { private val lock=Any() }
    data class Item(val sessionId:String,val payload:JsonObject,val state:String,val error:String?,val receipt:JsonObject?)
    private fun file(id:String):File { require(UUID.fromString(id).toString()==id);return File(root,"$id.json") }
    private fun normalized(value:JsonObject)=JsonDocuments.read(JsonDocuments.write(value))
    private fun read(f:File):Item {
        require(f.length() in 1..2_200_000)
        val value=JsonDocuments.read(f.readText())
        @Suppress("UNCHECKED_CAST")
        return Item(value["sessionId"] as String,value["payload"] as JsonObject,value["state"] as String,value["error"] as? String,value["receipt"] as? JsonObject)
    }
    private fun write(item:Item) {
        root.mkdirs()
        val target=file(item.sessionId)
        val text=JsonDocuments.write(mapOf("sessionId" to item.sessionId,"payload" to item.payload,"state" to item.state,"error" to item.error,"receipt" to item.receipt))
        require(text.toByteArray(Charsets.UTF_8).size<=2_200_000)
        val pending=File.createTempFile("record-",".tmp",root)
        try {
            FileOutputStream(pending).use { it.write(text.toByteArray(Charsets.UTF_8));it.fd.sync() }
            Files.move(pending.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally { pending.delete() }
    }
    fun items():List<Item> = synchronized(lock) {
        root.listFiles()?.filter { it.extension=="json" }?.sortedBy { it.name }?.map(::read) ?: emptyList()
    }
    fun enqueue(sessionId:String,report:EvaluationReport,startedEpochMs:Long,scenarioId:String?=null):Item = synchronized(lock) {
        require(report.origin in setOf(DataOrigin.LIVE,DataOrigin.SYNTHETIC))
        val summary=TraceReplay.summary(report)
        val target=file(sessionId)
        if(target.exists()) {
            val old=read(target)
            require(old.payload["report"]==normalized(summary)) { "같은 세션의 다른 평가 결과가 있습니다" }
            return@synchronized old
        }
        require(items().count { it.state!="SENT" }<200) { "전송 대기 기록이 200개입니다. 서버 연결을 확인해주세요" }
        val duration=report.endMs-report.startMs
        require(duration in 1..86_400_000 && startedEpochMs>=0)
        val payload=mapOf("schemaVersion" to 1,"origin" to report.origin.name,"startedAt" to Instant.ofEpochMilli(startedEpochMs).toString(),
            "endedAt" to Instant.ofEpochMilli(startedEpochMs+duration).toString(),"durationMs" to duration,"engineVersion" to report.engineVersion,
            "ruleVersion" to report.config.ruleVersion,"environmentRuleVersion" to when(report.engineVersion) { "0.7.0" -> "environment-0.3";"0.6.0" -> "environment-0.2";"0.5.0" -> "environment-0.1";else -> null },
            "scenarioId" to scenarioId,"report" to summary)
        require(JsonDocuments.write(payload).toByteArray(Charsets.UTF_8).size<=2_097_152) { "서버 전송 기록 용량을 초과했습니다" }
        Item(sessionId,normalized(payload),"PENDING",null,null).also(::write)
    }
    fun sent(id:String,receipt:JsonObject) = synchronized(lock) {
        require(receipt["sessionId"]==id) { "서버 저장 응답의 세션이 다릅니다" }
        write(read(file(id)).copy(state="SENT",error=null,receipt=receipt))
    }
    fun failed(id:String,error:String,terminal:Boolean) = synchronized(lock) {
        val old=read(file(id));if(old.state!="SENT")write(old.copy(state=if(terminal) "FAILED" else "PENDING",error=error))
    }
    fun retryFailed() = synchronized(lock) {items().filter { it.state=="FAILED" }.forEach { write(it.copy(state="PENDING",error=null)) }}
}
