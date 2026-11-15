package org.eowoo.routy.pleos.data.records

import org.eowoo.routy.evaluation.*
import java.io.File
import java.util.UUID
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class ArchiveRecoveryTest {
    @get:Rule val folder=TemporaryFolder()
    private val root get()=File(folder.root,"traces")
    private val queue get()=RecordOutbox(File(folder.root,"outbox"))
    private fun archive(id:String=UUID.randomUUID().toString()):TripArchive {
        val a=TripArchive(File(root,id),100,1000)
        a.append(200,"speed",mapOf("atMs" to 200,"value" to 1.0,"available" to true))
        return a
    }
    @Test fun completedCaptureRecoversEvaluationAndEnqueuesExactlyOnce() {
        val a=archive();a.finish(1000)
        assertEquals(ArchiveRecovery.Result(1,0),ArchiveRecovery(root,queue).run())
        val first=queue.items().single()
        assertEquals("PENDING",first.state);assertNotNull(first.archive)
        assertEquals(ArchiveRecovery.Result(0,0),ArchiveRecovery(root,queue).run())
        assertEquals(first,queue.items().single())
    }
    @Test fun interruptedRecordingRecoversAsExplicitlyInterrupted() {
        val a=archive();a.close()
        assertEquals(1,ArchiveRecovery(root,queue).run().recovered)
        val report=queue.items().single().payload["report"] as Map<*,*>
        assertEquals("INTERRUPTED",(report["recovery"] as Map<*,*>)["status"])
    }
    @Test fun activeTripIsNeverClaimedByRecovery() {
        val a=archive();val id=a.directory.name
        assertTrue(ArchiveRecovery.claim(id))
        try {assertEquals(ArchiveRecovery.Result(0,0),ArchiveRecovery(root,queue).run());assertTrue(queue.items().isEmpty())}
        finally {ArchiveRecovery.release(id);a.close()}
    }
    @Test fun existingUploadProgressSurvivesMissingCompletionMarker() {
        val a=archive();a.finish(1000)
        val item=queue.enqueueArchive(a.directory.name,a.evaluate(),a.startedEpochMs)
        queue.progress(item.sessionId,1)
        assertEquals(1,ArchiveRecovery(root,queue).run().recovered)
        assertEquals(1,queue.items().single().archive?.nextPart)
        assertEquals(item.payload,queue.items().single().payload)
    }
    @Test fun corruptOneTripDoesNotPreventOtherRecoveries() {
        val broken=archive();broken.finish(1000)
        File(broken.directory,"raw-0.ndjson").appendText("broken")
        archive().finish(1000)
        assertEquals(ArchiveRecovery.Result(1,1),ArchiveRecovery(root,queue).run())
        assertEquals(1,queue.items().size)
        assertTrue(File(broken.directory,"recovery-error.json").exists())
        assertFalse(File(broken.directory,"complete.json").exists())
    }
}
