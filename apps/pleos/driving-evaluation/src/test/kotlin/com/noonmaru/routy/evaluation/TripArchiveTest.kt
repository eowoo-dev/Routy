package com.noonmaru.routy.evaluation

import java.io.File
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class TripArchiveTest {
    @get:Rule val folder=TemporaryFolder()
    private fun row(at:Long,value:Double=10.0)=mapOf("atMs" to at,"value" to value,"available" to true)
    @Test fun twoHourHighRateInputIsPersistedInBoundedWindowsAndLateEvidenceIsEvaluated() {
        val archive=TripArchive(File(folder.root,"trip"),0,1_700_000_000_000)
        val end=7_200_000L
        for(at in 0L until end step 100) {
            val speed=if(at<end-2000)20.0 else 20.0-(at-(end-2000))*.008
            archive.append(at,"speed",row(at,speed))
            archive.append(at,"steering",row(at,0.0))
        }
        archive.finish(end)
        assertTrue(archive.segments>=120)
        var total=0;var largest=0L
        for(i in 0 until archive.segments){total+=archive.session(i).speeds.size;largest=maxOf(largest,File(archive.directory,"raw-$i.ndjson").length())}
        assertEquals(72_000,total);assertTrue(largest<524_288+TripArchive.ROW_BYTES)
        val result=archive.evaluate()
        assertEquals(end,result.report.endMs)
        assertTrue(result.report.assessments.all { ExclusionReason.WINDOW_BOUNDARY in it.reasons })
        val finalReport=JsonDocuments.read(File(archive.directory,"evaluation-${archive.segments-1}.json").readText())
        assertTrue(TraceReplay.summary(DrivingEvaluationEngine().evaluate(TraceReplay.session(finalReport)))["events"].toString().contains("RAPID_DECELERATION"))
        assertEquals(TripArchive.sha256(result.archive),result.sha256)
        assertTrue(result.report.events.size<=WindowReportSummary.PREVIEW)
    }
    @Test fun durationBeyondOneDayDoesNotTruncateTheArchive() {
        val archive=TripArchive(File(folder.root,"day"),10,0)
        archive.append(10,"speed",row(10))
        archive.append(129_600_010,"speed",row(129_600_010))
        archive.finish(129_600_011)
        assertEquals(129_600_001,archive.evaluate().report.endMs-10)
    }
    @Test fun explicitAcquisitionGapContinuesAcrossWindowBoundaries() {
        val archive=TripArchive(File(folder.root,"gaps"),0,0,windowMs=1000)
        archive.append(10,"speed",row(10))
        archive.append(100,"speedGapStart",mapOf("atMs" to 10))
        archive.append(1100,"tick",emptyMap())
        archive.append(2100,"speedGapEnd",mapOf("atMs" to 2100))
        archive.append(2100,"speed",row(2100));archive.finish(2200)
        val ranges=(0 until archive.segments).flatMap{archive.session(it).speedGaps}
        assertEquals(listOf(TimeRange(10,1100),TimeRange(1100,2100)),ranges)
    }
    @Test fun damagedOrIncompleteRawDataCannotBecomeASavedEvaluation() {
        val archive=TripArchive(File(folder.root,"damaged"),0,0)
        archive.append(10,"speed",row(10))
        assertFails { archive.evaluate() }
        archive.finish(1000)
        File(archive.directory,"raw-0.ndjson").appendText("corrupt")
        assertFails { archive.evaluate() }
        assertFalse(File(archive.directory,"summary.json").exists())
    }
    @Test fun storageAndOversizedRowFailuresAreExplicit() {
        val blocked=File(folder.root,"file").apply{writeText("x")}
        assertFails { TripArchive(File(blocked,"trip"),0,0) }
        val archive=TripArchive(File(folder.root,"large-row"),0,0)
        assertFails { archive.append(1,"readings",mapOf("large" to "x".repeat(TripArchive.ROW_BYTES))) }
        archive.close()
    }
    @Test fun interruptedSealedWindowsAreNeverPublishedAsACompleteDrive() {
        val archive=TripArchive(File(folder.root,"interrupted"),0,0,windowMs=1000)
        archive.append(10,"speed",row(10));archive.append(1100,"speed",row(1100))
        archive.close()
        assertTrue(File(archive.directory,"window-0.json").exists())
        assertFails{archive.evaluate()}
    }
    @Test fun summaryStaysBoundedWhileAllWindowDetailsRemainAvailable() {
        val summary=WindowReportSummary()
        for(i in 0 until 1000) {
            val report=DrivingEvaluationEngine().evaluate(DrivingSession(i*1000L,(i+1)*1000L,DataOrigin.LIVE))
            summary.add(report.copy(events=List(100){EvaluationEvent(EventKind.RAPID_STEERING,report.startMs,report.endMs,mapOf("index" to "$i"))}))
        }
        assertEquals(32,summary.report().events.size)
        assertEquals(1_000_000,summary.report().endMs)
        assertEquals("window-boundary",summary.report().feedback.first().id)
    }
    @Test fun byteRotationAndStalledClockCannotCreateAnUnboundedWindow() {
        val archive=TripArchive(File(folder.root,"bytes"),0,0,windowBytes=65_536)
        val body=mapOf("data" to "x".repeat(4000))
        repeat(100){archive.append(it.toLong(),"diagnostic",body)}
        archive.finish(101)
        assertTrue(archive.segments>1)
        for(i in 0 until archive.segments)assertTrue(File(archive.directory,"raw-$i.ndjson").length()<=65_536+TripArchive.ROW_BYTES)
        val stalled=TripArchive(File(folder.root,"clock"),0,0,windowBytes=65_536)
        assertFails {repeat(100){stalled.append(0,"diagnostic",body)}}
        stalled.close()
    }
    @Test fun assessedTurnOpportunitiesRemainPartialInsteadOfBecomingUnassessed() {
        val summary=WindowReportSummary()
        for(i in 0..1) {
            val r=DrivingEvaluationEngine().evaluate(DrivingSession(i*1000L,(i+1)*1000L,DataOrigin.LIVE))
            summary.add(r.copy(assessments=r.assessments.map{if(it.category==Category.TURN_SIGNAL_USE)
                it.copy(status=AssessmentStatus.ASSESSED,opportunities=1,evaluatedOpportunities=1) else it}))
        }
        val turn=summary.report().assessments.single{it.category==Category.TURN_SIGNAL_USE}
        assertEquals(AssessmentStatus.PARTIAL,turn.status);assertEquals(2,turn.evaluatedOpportunities)
    }
}
