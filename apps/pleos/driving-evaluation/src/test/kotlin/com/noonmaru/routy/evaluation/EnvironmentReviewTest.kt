package com.noonmaru.routy.evaluation

import org.junit.Test
import kotlin.test.*

class EnvironmentReviewTest {
    private fun frames()=(0L..1000L step 100).mapIndexed { index,t ->
        EnvironmentFrame("review",index.toLong(),t,0.0,0.0,0.0,10.0,"LEFT",emptyList(),"GREEN",1000.0)
    }
    private fun review(rows:List<EnvironmentFrame>)=EnvironmentEvaluation.evaluate(rows).review!!
    @Test fun validEmptyRoadIsNoOpportunityRatherThanMissingData() {
        val review=review(frames())
        val following=review.quality.first { it.channel=="FOLLOWING_DISTANCE" }
        assertEquals(1000L,following.noOpportunityMs); assertEquals(0L,following.excludedMs)
        assertEquals(1000L,review.quality.first { it.channel=="LANE_KEEPING" }.eligibleMs)
        assertEquals(1000L,review(frames().map { it.copy(signal="UNKNOWN") }).quality.first { it.channel=="LANE_CHANGE_SIGNAL" }.noOpportunityMs)
        val missing=EnvironmentEvaluation.evaluate(emptyList(),startMs=0,endMs=1000).review!!
        assertTrue(missing.quality.all { it.excludedMs==1000L && it.reasons["NO_OBSERVATION"]==1000L })
    }
    @Test fun qualityPartitionsEveryMillisecondAndDoesNotBridgeGaps() {
        val rows=frames().filter { it.atMs !in 200..700 }
        val result=review(rows)
        assertEquals(700L,result.quality.first().reasons["TIME_GAP"])
        result.quality.forEach { assertEquals(it.totalMs,it.eligibleMs+it.noOpportunityMs+it.excludedMs) }
    }
    @Test fun invalidEndpointsAndDuplicateOrReversedOrderAreExcluded() {
        for(rows in listOf(frames().map { if(it.atMs==500L) it.copy(available=false) else it },
            frames().toMutableList().apply { add(6,get(4)) },frames().asReversed())) {
            val q=review(rows).quality.first()
            assertTrue(q.excludedMs>0); assertTrue(q.eligibleMs<q.totalMs)
        }
    }
    @Test fun physicalMetricsUseBodyGapAndRelativeSpeed() {
        val rows=frames().map { it.copy(vehicles=listOf(EnvironmentVehicle("lead",15.0,0.0,0.0))) }
        val result=EnvironmentEvaluation.evaluate(rows)
        assertEquals(10.2,result.review!!.metrics["minGapM"]!!,1e-9)
        assertEquals(1.02,result.review!!.metrics["minHeadwayS"]!!,1e-9)
        assertEquals(1.02,result.review!!.metrics["minTtcS"]!!,1e-9)
        assertEquals(2,result.events.size)
        assertEquals(1,result.review!!.groups.size)
        assertTrue(result.events.all { it.subjectId=="lead" && it.evidence.isNotEmpty() })
    }
    @Test fun standstillOrOpeningGapDoesNotInventTtc() {
        val result=review(frames().map { it.copy(speed=0.0,vehicles=listOf(EnvironmentVehicle("lead",15.0,0.0,10.0))) })
        assertFalse("minHeadwayS" in result.metrics); assertFalse("minTtcS" in result.metrics)
    }
    @Test fun completedChangeIsSeparatedFromLaneKeepingAndSignalEvidenceIsNumeric() {
        val rows=(0L..5000L step 100).mapIndexed { i,t -> frames()[0].copy(sequence=i.toLong(),atMs=t,signal=if(t in 4200..4600) "OFF" else "LEFT") }
        val events=listOf(EnvironmentEvent("LANE_CHANGE_LEFT",4000,5000,"0 -> 1"),EnvironmentEvent("INTERRUPTED_LANE_CHANGE_SIGNAL",4000,5000,"test"))
        val enriched=EnvironmentReviewBuilder.enrich(rows,events)
        assertEquals(4000.0,enriched[1].evidence["signalPreparationMs"])
        assertEquals(500.0,enriched[1].evidence["signalOffDurationMs"])
        val result=EnvironmentReviewBuilder.build(rows,enriched)
        assertEquals(1000L,result.quality.first().reasons["INTENTIONAL_CHANGE"])
        assertEquals(1,result.groups.size); assertEquals(2,result.groups.single().kinds.size)
        assertTrue(result.groups.single().nextPractice.contains("해제"))
        val incomplete=EnvironmentReviewBuilder.build(rows,listOf(EnvironmentEvent("INCOMPLETE_LANE_CHANGE",4000,5000,"test")))
        assertFalse("INTENTIONAL_CHANGE" in incomplete.quality.first().reasons)
    }
    @Test fun separateLeadingObjectsRemainSeparateAndCutInAdviceAvoidsBlame() {
        val events=listOf(EnvironmentEvent("LEAD_VEHICLE_CUT_IN",0,100,"test",subjectId="a"),EnvironmentEvent("LOW_CLOSING_TTC",100,600,"test",subjectId="a"),EnvironmentEvent("SHORT_FOLLOWING_HEADWAY",600,1000,"test",subjectId="b"))
        val groups=EnvironmentReviewBuilder.build(frames(),events).groups
        assertEquals(2,groups.size); assertTrue(groups.first().nextPractice.contains("끼어든"))
    }
    @Test fun consecutiveLaneChangesRemainSeparateReviewSituations() {
        val events=listOf(EnvironmentEvent("LANE_CHANGE_LEFT",0,400,"first"),EnvironmentEvent("LANE_CHANGE_SIGNAL_OBSERVED",0,400,"first"),
            EnvironmentEvent("LANE_CHANGE_RIGHT",500,1000,"second"),EnvironmentEvent("MISSING_LANE_CHANGE_SIGNAL",500,1000,"second"))
        val groups=EnvironmentReviewBuilder.build(frames(),events).groups
        assertEquals(2,groups.size);assertTrue(groups.all { it.kinds.size==2 })
    }
    @Test fun integratedExclusionsKeepTheirSpecificReason() {
        val base=ScenarioReview.session(frames()).copy(origin=DataOrigin.LIVE)
        val cases=listOf(
            base.copy(environment=base.environment.map { it.copy(uncertaintyMs=101) }) to "CLOCK_UNCERTAIN",
            base.copy(speeds=base.speeds.map { it.copy(value=0.0) }) to "SDK_MISMATCH",
            base.copy(speeds=emptyList()) to "SDK_MISSING",
            base.copy(speedGaps=listOf(TimeRange(0,1000))) to "SDK_GAP",
            base.copy(environment=base.environment.map { it.copy(frame=it.frame.copy(d=8.0)) }) to "OUT_OF_SCOPE"
        )
        cases.forEach { (input,reason) ->
            val q=DrivingEvaluationEngine().evaluate(input).environment!!.review!!.quality.first()
            assertEquals(0L,q.eligibleMs,reason); assertTrue(reason in q.reasons,reason)
        }
    }
    @Test fun syntheticRecordingKeepsFullSummaryAndRejectsAlteredAnalysis() {
        val input=frames(); val engine=DrivingEvaluationEngine();val report=engine.evaluate(ScenarioReview.session(input))
        val document=JsonDocuments.read(JsonDocuments.write(ScenarioReview.recording(input,report)))
        val replay=engine.evaluate(TraceReplay.session(document))
        assertEquals(DataOrigin.SYNTHETIC,replay.origin); assertEquals("0.7.0",replay.engineVersion)
        TraceReplay.verifyCaptured(document,replay)
        assertFails { TraceReplay.verifyCaptured(document+mapOf("analysis" to emptyMap<String,Any>()),replay) }
    }
    @Test fun completedDownloadRejectsResetCountMismatchAndOtherRuns() {
        val rows=frames();val state=ScenarioCompletion("completed","test","review",rows.size,1000)
        ScenarioCompletion.verify(state,state,rows)
        assertFails { ScenarioCompletion.verify(state,state.copy(status="ready"),rows) }
        assertFails { ScenarioCompletion.verify(state,state,rows.drop(1)) }
        assertFails { ScenarioCompletion.verify(state,state,rows.map { it.copy(runId="other") }) }
        assertEquals("",ScenarioCompletion.decode("""{"status":"idle","scenarioId":null,"frame":null,"count":0}""").runId)
    }
    @Test fun previousEnvironmentVersionStillReplaysWithoutNewFields() {
        val input=ScenarioReview.session(frames()).copy(origin=DataOrigin.LIVE,environmentRuleVersion="environment-0.2")
        val engine=DrivingEvaluationEngine();val report=engine.evaluate(input)
        assertEquals("0.6.0",report.engineVersion);assertNull(report.environment!!.review)
        val doc=JsonDocuments.read(JsonDocuments.write(TraceReplay.recording(input,report)))
        TraceReplay.verifyCaptured(doc,engine.evaluate(TraceReplay.session(doc)))
    }
}
