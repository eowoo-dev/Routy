package com.noonmaru.routy.pleos.data.records

import com.noonmaru.routy.evaluation.*
import java.io.File
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class ArchiveTransferTest {
    @get:Rule val folder=TemporaryFolder()
    private val id=UUID.randomUUID().toString()
    private fun fixture():Pair<TripArchive.Evaluation,RecordOutbox> {
        val file=File(folder.root,"reports.ndjson")
        file.outputStream().use{output->repeat(20){index->output.write(ByteArray(TripArchive.PART_BYTES){(it+index).toByte()})}}
        val report=DrivingEvaluationEngine().evaluate(DrivingSession(1,129_600_001,DataOrigin.LIVE))
        val value=TripArchive.Evaluation(report,TraceReplay.summary(report),file,20,file.length(),TripArchive.sha256(file))
        return value to RecordOutbox(File(folder.root,"queue"))
    }
    private class Server:ArchiveTransfer.Server {
        val parts=linkedMapOf<Int,ByteArray>();val calls=mutableListOf<Int>();var completeCalls=0
        var loseReceipt:Int?=null;var wrongHash=false;var offline=false
        override fun part(index:Int,bytes:ByteArray,sha256:String):JsonObject {
            if(offline)throw IOException("offline")
            calls+=index
            assertTrue(bytes.size<=TripArchive.PART_BYTES)
            parts[index]?.let{assertContentEquals(it,bytes)}?:run{parts[index]=bytes}
            if(loseReceipt==index){loseReceipt=null;throw IOException("receipt lost")}
            return mapOf("index" to index,"sha256" to if(wrongHash)"bad" else sha256)
        }
        override fun complete(record:JsonObject,archive:JsonObject):JsonObject {
            completeCalls++;assertEquals((archive["parts"] as Number).toInt(),parts.size)
            return mapOf("sessionId" to record["testSessionId"])
        }
    }
    private fun serverFor(sessionId:String,base:Server)=object:ArchiveTransfer.Server {
        override fun part(index:Int,bytes:ByteArray,sha256:String)=base.part(index,bytes,sha256)
        override fun complete(record:JsonObject,archive:JsonObject)=base.complete(record+mapOf("testSessionId" to sessionId),archive)
    }
    @Test fun archiveAboveTwoMiBAndThirtySixHourDurationSurvivesQueueAndUpload() {
        val (value,queue)=fixture();queue.enqueueArchive(id,value,1_700_000_000_000)
        val reopened=RecordOutbox(File(folder.root,"queue"));val item=reopened.items().single()
        assertEquals(129_600_000L,(item.payload["durationMs"] as Number).toLong())
        assertTrue(value.bytes>2_097_152)
        val server=Server();ArchiveTransfer.send(reopened,item,value.archive,serverFor(id,server))
        assertEquals("SENT",reopened.items().single().state);assertEquals(1,server.completeCalls)
        val received=ByteArrayOutputStream();server.parts.values.forEach{received.write(it)}
        assertContentEquals(value.archive.readBytes(),received.toByteArray())
    }
    @Test fun lostReceiptResendsOnlyUnacknowledgedPartWithoutDuplicatingData() {
        val (value,queue)=fixture();val item=queue.enqueueArchive(id,value,0)
        val server=Server().apply{loseReceipt=1};val target=serverFor(id,server)
        assertFailsWith<IOException>{ArchiveTransfer.send(queue,item,value.archive,target)}
        val resumed=RecordOutbox(File(folder.root,"queue")).items().single()
        assertEquals(1,resumed.archive!!.nextPart);assertEquals("PENDING",resumed.state)
        ArchiveTransfer.send(queue,resumed,value.archive,target)
        assertEquals(listOf(0,1,1),(server.calls.take(3)));assertEquals(20,server.parts.size)
        assertEquals("SENT",queue.items().single().state)
    }
    @Test fun corruptOrMissingDiskArchiveNeverReachesTheServer() {
        val (value,queue)=fixture();val item=queue.enqueueArchive(id,value,0);val server=Server()
        value.archive.appendText("damage")
        assertFails{ArchiveTransfer.send(queue,item,value.archive,serverFor(id,server))}
        assertTrue(server.calls.isEmpty());assertEquals("PENDING",queue.items().single().state)
    }
    @Test fun wrongAcknowledgementAndCancellationDoNotAdvanceTheQueue() {
        val (value,queue)=fixture();val item=queue.enqueueArchive(id,value,0);val server=Server().apply{wrongHash=true}
        assertFails{ArchiveTransfer.send(queue,item,value.archive,serverFor(id,server))}
        assertEquals(0,queue.items().single().archive!!.nextPart)
        assertFailsWith<IOException>{ArchiveTransfer.send(queue,item,value.archive,serverFor(id,server),stopped={true})}
        assertEquals(0,server.completeCalls)
    }
}
