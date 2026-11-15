package org.eowoo.routy.pleos.data.records

import org.eowoo.routy.evaluation.*
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Same-process acquisition and recovery claim a session before touching its files. */
class ArchiveRecovery(private val root:File,private val outbox:RecordOutbox) {
    companion object {
        private val active=ConcurrentHashMap.newKeySet<String>()
        fun claim(id:String)=active.add(id)
        fun release(id:String){active.remove(id)}
    }
    data class Result(val recovered:Int,val failed:Int)
    fun run():Result {
        var recovered=0;var failed=0
        for(directory in root.listFiles().orEmpty().filter{it.isDirectory}.sortedBy{it.name}) {
            val id=directory.name
            if(runCatching{UUID.fromString(id).toString()==id}.getOrDefault(false).not() || !File(directory,"manifest.json").exists())continue
            if(File(directory,"complete.json").exists() || !claim(id))continue
            try {
                // An upload may already have been enqueued before the completion marker was written.
                // Preserve its immutable payload and progress, including reports from an older engine.
                val existing=outbox.find(id)
                if(existing!=null) {
                    existing.archive?.let{require(TripArchive.sha256(File(directory,"reports.ndjson"))==it.sha256)}
                    TripArchive.atomic(File(directory,"complete.json"),mapOf("sessionId" to id,"recoveredOutbox" to true))
                } else {
                    TripArchive.recover(directory).use { archive ->
                        val evaluation=archive.evaluate()
                        outbox.enqueueArchive(id,evaluation,archive.startedEpochMs)
                        val index=mapOf("schemaVersion" to 2,"storage" to "SEGMENTED","sessionId" to id,"origin" to "LIVE",
                            "startMs" to evaluation.report.startMs,"endMs" to evaluation.report.endMs,"segments" to archive.segments,
                            "truncated" to (evaluation.summary["recovery"]!=null),"analysis" to evaluation.summary,"rawDirectory" to id)
                        TripArchive.atomic(File(root,"$id.json"),index)
                        TripArchive.atomic(File(directory,"complete.json"),index)
                    }
                }
                File(directory,"recovery-error.json").delete()
                recovered++
            } catch(e:Exception) {
                failed++
                TripArchive.atomic(File(directory,"recovery-error.json"),mapOf("sessionId" to id,"error" to (e.message ?: e.javaClass.simpleName)))
            } finally {release(id)}
        }
        return Result(recovered,failed)
    }
}
