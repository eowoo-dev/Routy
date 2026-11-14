package com.noonmaru.routy.pleos.data.records

import com.noonmaru.routy.evaluation.*
import com.noonmaru.routy.contracts.infrastructure.Serializer
import com.noonmaru.routy.contracts.model.DrivingRecordUpload
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID
import kotlin.test.*

class RecordOutboxTest {
    @get:Rule val folder=TemporaryFolder()
    private fun report()=DrivingEvaluationEngine().evaluate(ExampleDrive.session())
    @Test fun pendingPayloadSurvivesAReopenedQueueAndGeneratedContractRoundTrip() {
        val root=folder.newFolder();val queue=RecordOutbox(root);val id=UUID.randomUUID().toString()
        val item=queue.enqueue(id,report(),1_700_000_000_000)
        assertEquals(item,RecordOutbox(root).items().single())
        val model=Serializer.moshi.adapter(DrivingRecordUpload::class.java).fromJson(JsonDocuments.write(item.payload))!!
        assertEquals(1,model.schemaVersion);assertEquals(12_000,model.durationMs)
        assertEquals(DrivingRecordUpload.Origin.SYNTHETIC,model.origin)
    }
    @Test fun sameSessionKeepsOriginalWallClockAndAcknowledgementAcrossReenqueue() {
        val queue=RecordOutbox(folder.newFolder());val id=UUID.randomUUID().toString()
        val first=queue.enqueue(id,report(),1_700_000_000_000)
        queue.sent(id,mapOf("id" to UUID.randomUUID().toString(),"sessionId" to id))
        val again=queue.enqueue(id,report(),1_800_000_000_000)
        assertEquals(first.payload,again.payload);assertEquals("SENT",again.state);assertEquals(1,queue.items().size)
    }
    @Test fun conflictingPayloadCannotOverwriteTheOriginal() {
        val queue=RecordOutbox(folder.newFolder());val id=UUID.randomUUID().toString()
        val first=queue.enqueue(id,report(),1_700_000_000_000)
        assertFails {queue.enqueue(id,report().copy(engineVersion="0.7.9"),1_700_000_000_000)}
        assertEquals(first,queue.items().single())
    }
    @Test fun retryableAndTerminalFailuresRetainPayloadAndCanBeRetriedExplicitly() {
        val root=folder.newFolder();val queue=RecordOutbox(root);val id=UUID.randomUUID().toString()
        val first=queue.enqueue(id,report(),1_700_000_000_000)
        queue.failed(id,"offline",false);assertEquals("PENDING",RecordOutbox(root).items().single().state)
        queue.failed(id,"409",true);assertEquals("FAILED",RecordOutbox(root).items().single().state)
        queue.retryFailed();val restored=queue.items().single();assertEquals("PENDING",restored.state);assertEquals(first.payload,restored.payload)
    }
    @Test fun unrelatedServerReceiptDoesNotAcknowledgeOrDeletePendingData() {
        val queue=RecordOutbox(folder.newFolder());val id=UUID.randomUUID().toString();queue.enqueue(id,report(),1_700_000_000_000)
        assertFails {queue.sent(id,mapOf("sessionId" to UUID.randomUUID().toString()))}
        assertEquals("PENDING",queue.items().single().state)
    }
    @Test fun invalidPathsAndReplayedActualRecordsCannotBeQueued() {
        val queue=RecordOutbox(folder.newFolder())
        assertFails {queue.enqueue("../escape",report(),1_700_000_000_000)}
        assertFails {queue.enqueue(UUID.randomUUID().toString(),report().copy(origin=DataOrigin.RECORDED),1_700_000_000_000)}
    }
}
