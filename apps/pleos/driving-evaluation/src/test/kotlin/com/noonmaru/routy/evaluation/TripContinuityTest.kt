package com.noonmaru.routy.evaluation

import kotlin.test.*
import java.nio.file.Files
import java.io.File

class TripContinuityTest {
    private fun frames()=(0L..30_000L step 100).map { at ->
        val d=when { at<5000->0.0;at<24000->1.3;at<26000->1.3+2.4*(at-24000)/2000.0;else->3.7 }
        EnvironmentFrame("long-change",at/100,at,at/100.0,d,0.0,10.0,
            if(at in 12_000L until 12_500L)"OFF" else "LEFT",emptyList(),"GREEN",10000.0)
    }
    @Test fun longChangeKeepsPreparationAndMidChangeSignalInterruptionAcrossSixWindows() {
        val rows=frames();val state=EnvironmentEvaluation.Stream()
        val events=rows.chunked(50).flatMapIndexed { i,chunk ->
            state.consume(chunk,startMs=chunk.first().atMs,endMs=chunk.last().atMs+1,final=i==rows.chunked(50).lastIndex).events
        }
        val batch=EnvironmentEvaluation.evaluate(rows)
        assertEquals(batch.events.map{Triple(it.kind,it.startMs,it.endMs)},events.map{Triple(it.kind,it.startMs,it.endMs)})
        val signal=events.single{it.kind=="INTERRUPTED_LANE_CHANGE_SIGNAL"}
        assertEquals(500.0,signal.evidence["signalOffDurationMs"])
        assertEquals(5000.0,signal.evidence["signalPreparationMs"])
        assertEquals(1,events.count{it.kind=="LANE_CHANGE_LEFT"})
        assertFalse(events.any{it.kind=="INCOMPLETE_LANE_CHANGE"})
    }
    @Test fun accelerationEpisodeSurvivesManySubThresholdWindowsWithoutDuplicateCounts() {
        val directory=Files.createTempDirectory("short-windows").toFile()
        try {
            val archive=TripArchive(File(directory,"trip"),0,1000,windowMs=400)
            for(at in 0L..4000L step 100)archive.append(at,"speed",mapOf("atMs" to at,"value" to minOf(at,3000L)*.004,"available" to true))
            archive.finish(4100)
            val result=archive.evaluate()
            assertTrue(archive.segments>8)
            assertEquals(1L,(result.summary["segmentation"].jsonObject()["eventCounts"].jsonObject()["RAPID_ACCELERATION"] as Number).toLong())
            assertEquals(1,result.report.events.count{it.kind==EventKind.RAPID_ACCELERATION})
            assertTrue(result.report.events.single{it.kind==EventKind.RAPID_ACCELERATION}.endMs>3000)
        } finally {directory.deleteRecursively()}
    }
    @Test fun diskArchiveReportsOneLongLaneChangeAcrossFifteenWindows() {
        val directory=Files.createTempDirectory("environment-windows").toFile()
        try {
            val archive=TripArchive(File(directory,"trip"),0,1000,windowMs=2000)
            for(f in frames()) {
                archive.append(f.atMs,"speed",mapOf("atMs" to f.atMs,"value" to f.speed,"available" to true))
                archive.append(f.atMs,"environment",EnvironmentIntegration.encode(listOf(AlignedEnvironmentFrame(f,f.atMs,0))).single())
            }
            archive.finish(30001)
            val result=archive.evaluate()
            val counts=result.summary["segmentation"].jsonObject()["environmentEventCounts"].jsonObject()
            assertEquals(1L,(counts["LANE_CHANGE_LEFT"] as Number).toLong())
            assertNull(counts["INCOMPLETE_LANE_CHANGE"])
            val review=assertNotNull(result.report.environment?.review)
            val expected=EnvironmentEvaluation.evaluate(frames()).events.single{it.kind=="LANE_CHANGE_LEFT"}
            assertTrue(review.groups.any{g->"LANE_CHANGE_LEFT" in g.kinds && g.startMs==expected.startMs && g.endMs==expected.endMs})
            assertTrue(result.report.feedback.any{it.id.startsWith("environment-")})
        } finally {directory.deleteRecursively()}
    }
    @Test fun redStopEvidenceKeepsTheMinimumGapAcrossWindows() {
        val rows=(0L..1000L step 100).map{at->EnvironmentFrame("red",at/100,at,at/20000.0,0.0,0.0,.05,"OFF",emptyList(),"RED",5.0)}
        val stream=EnvironmentEvaluation.Stream()
        stream.consume(rows.take(5),startMs=0,endMs=500)
        val event=stream.consume(rows.drop(5),startMs=500,endMs=1001,final=true).events.single{it.kind=="STOPPED_BEFORE_RED"}
        val reference=EnvironmentEvaluation.evaluate(rows).events.single{it.kind=="STOPPED_BEFORE_RED"}
        assertEquals(reference.evidence["redStopLineGapM"],event.evidence["redStopLineGapM"])
    }
    @Test fun realGapStillEndsPendingManeuver() {
        val rows=frames();val state=EnvironmentEvaluation.Stream()
        state.consume(rows.filter{it.atMs<10000},startMs=0,endMs=10000)
        val result=state.consume(rows.filter{it.atMs>=20000},startMs=10000,endMs=30001,final=true)
        assertTrue(result.events.any{it.kind=="INCOMPLETE_LANE_CHANGE"})
        assertFalse(result.events.any{it.kind=="INTERRUPTED_LANE_CHANGE_SIGNAL"})
    }
    @Test fun overlappingBasicEventsAreCountedOnceButSeparateEpisodesRemainSeparate() {
        val ledger=mutableListOf<EvaluationEvent>();val journal=TripEventJournal{ledger+=it}
        fun event(a:Long,b:Long,road:String="a")=EvaluationEvent(EventKind.SUSTAINED_SPEED_EXCESS,a,b,mapOf("roadId" to road))
        journal.accept(listOf(event(1000,60000)),0)
        journal.accept(listOf(event(50000,120000)),50000)
        journal.accept(listOf(event(110000,130000),event(140000,145000),event(144000,150000,"b")),130000,final=true)
        assertEquals(listOf(1000L to 130000L,140000L to 145000L,144000L to 150000L),ledger.map{it.startMs to it.endMs})
        assertEquals(3L,journal.counts.values.sum())
    }
    @Test fun summaryRetainsBoundedEnvironmentReviewAndDisjointQuality() {
        val summary=WindowReportSummary()
        repeat(2) { i ->
            val start=i*1000L
            val report=DrivingEvaluationEngine().evaluate(DrivingSession(start,start+1000,DataOrigin.SYNTHETIC))
            val review=EnvironmentReview(listOf(EnvironmentCoverage("LANE_KEEPING",1000,900,0,100,mapOf("TIME_GAP" to 100))),emptyList(),mapOf("redStopLineGapM" to (2.0-i)),
                listOf(EnvironmentReviewGroup("environment-$i",start,start+500,"주의","다음 연습",listOf("LANE_BOUNDARY_APPROACH"),emptyMap(),false)))
            summary.add(report.copy(environment=EnvironmentResult("SIMULATED_ENVIRONMENT",10,0,emptyList(),review)))
        }
        val review=assertNotNull(summary.report().environment?.review)
        assertEquals(2,review.groups.size);assertEquals(1800L,review.quality.single().eligibleMs)
        assertEquals(200L,review.quality.single().reasons["TIME_GAP"])
        assertEquals(1.0,review.metrics["redStopLineGapM"])
    }
}
