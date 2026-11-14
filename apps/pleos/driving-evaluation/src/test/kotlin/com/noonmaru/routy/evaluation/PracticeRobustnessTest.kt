package com.noonmaru.routy.evaluation

import kotlin.test.*

class PracticeRobustnessTest {
    private val engine=DrivingEvaluationEngine()
    private fun session()=PracticeScenarios.session("left-missing")
    @Test fun indicatorSelectionDoesNotDriveTurnDetection() {
        val input=session()
        val a=engine.evaluate(input)
        val b=engine.evaluate(input.copy(signals=input.signals.map { it.copy(value=SignalSelection.RIGHT) }))
        assertEquals(a.practice.maneuvers,b.practice.maneuvers)
        assertEquals(1,a.turns.evaluated)
        assertTrue(a.events.any { it.kind==EventKind.MISSING_TURN_SIGNAL })
        assertTrue(b.events.any { it.kind==EventKind.OPPOSITE_TURN_SIGNAL })
    }
    @Test fun headingWrapAroundStillDetectsLeftTurn() {
        val r=engine.evaluate(session())
        assertTrue(r.practice.maneuvers.any { it.kind==ManeuverKind.LEFT_TURN && it.headingChangeDeg < -80 })
    }
    @Test fun duplicateOrOutOfOrderPositionsInvalidateDetection() {
        val s=session()
        for (p in listOf(listOf(s.positions.first())+s.positions,s.positions.reversed())) {
            val r=engine.evaluate(s.copy(positions=p))
            assertTrue(r.practice.confirmedTurns.isEmpty())
            assertTrue(r.events.none { it.kind==EventKind.MISSING_TURN_SIGNAL })
        }
    }
    @Test fun unknownClockAndMissingAccuracyCannotConfirmTurns() {
        val s=session()
        assertTrue(engine.evaluate(s.copy(positionTimeBasis=SteeringTimeBasis.UNKNOWN)).practice.maneuvers.isEmpty())
        val r=engine.evaluate(s.copy(positions=s.positions.map { it.copy(value=it.value!!.copy(accuracyM=null)) }))
        assertTrue(r.practice.confirmedTurns.isEmpty())
        assertTrue(r.practice.roadMatches.all { it.status==MatchStatus.ACCURACY_UNKNOWN })
    }
    @Test fun expiredMapsAndGeometricCrossingsWithoutTopologyCannotConfirmTurns() {
        val s=session();val n=s.roadNetwork!!
        val expired=engine.evaluate(s.copy(roadNetwork=n.copy(segments=n.segments.map { it.copy(validUntilMs=0) })))
        assertTrue(expired.practice.confirmedTurns.isEmpty())
        val disconnected=engine.evaluate(s.copy(roadNetwork=n.copy(segments=n.segments.map { it.copy(fromNode="entry-${it.id}",toNode="exit-${it.id}") })))
        assertTrue(disconnected.practice.confirmedTurns.isEmpty())
    }
    @Test fun syntheticMapsCannotLeakIntoLiveEvaluation() {
        val r=engine.evaluate(session().copy(origin=DataOrigin.LIVE))
        assertTrue(r.practice.roadMatches.all { it.status==MatchStatus.INVALID_MAP })
        assertTrue(r.practice.confirmedTurns.isEmpty())
    }
    @Test fun staticGpsWhileSpeedSaysMovingIsNotStraightDriving() {
        val s=session();val p=s.positions.first().value!!
        val r=engine.evaluate(s.copy(positions=s.positions.map { it.copy(value=p) }))
        assertTrue(r.practice.maneuvers.isEmpty())
        assertTrue("POSITION_MOTION_INCONSISTENT" in r.practice.quality.first().reasons)
    }
    @Test fun teleportAndSpeedGapsBreakManeuverContinuity() {
        val s=session()
        val jumped=s.positions.mapIndexed { i,p -> if (i==12) p.copy(value=p.value!!.copy(point=GeoPoint(38.0,128.0))) else p }
        for (input in listOf(s.copy(positions=jumped),s.copy(speedGaps=listOf(TimeRange(4_000,8_000))))) {
            assertTrue(engine.evaluate(input).practice.confirmedTurns.isEmpty())
        }
    }
    @Test fun unknownHazardsHoldSignalJudgmentEvenForConfirmedTurns() {
        val r=engine.evaluate(session().copy(hazardStateKnown=false))
        assertEquals(1,r.practice.confirmedTurns.size)
        assertEquals(0,r.turns.evaluated)
        assertTrue(ExclusionReason.HAZARD_CONTEXT_UNKNOWN in r.assessments.single { it.category==Category.TURN_SIGNAL_USE }.reasons)
    }
    @Test fun steeringReviewsAreGroupedAndUseStraightRoadContext() {
        val r=engine.evaluate(PracticeScenarios.session("straight-corrections"))
        val rows=r.feedback.filter { EventKind.REPEATED_STEERING_REVERSALS in it.eventKinds || EventKind.RAPID_STEERING in it.eventKinds }
        assertEquals(1,rows.size)
        assertTrue("직진 구간" in rows.single().title)
        assertEquals(ReviewPriority.PRACTICE,rows.single().priority)
    }
    @Test fun newRecordingReplaysAllPracticeQualityAndFeedbackAndRejectsTampering() {
        val synthetic=session()
        val live=synthetic.copy(origin=DataOrigin.LIVE,roadNetwork=synthetic.roadNetwork!!.copy(provenance=RoadProvenance.VERIFIED_MAP))
        val report=engine.evaluate(live)
        val document=JsonDocuments.read(JsonDocuments.write(TraceReplay.recording(live,report)))
        val replay=engine.evaluate(TraceReplay.session(document))
        TraceReplay.verifyCaptured(document,replay)
        assertEquals(report.feedback,replay.feedback)
        val bad=document.toMutableMap();bad["analysis"] = document["analysis"].jsonObject() + ("feedback" to emptyList<Any>())
        assertFailsWith<IllegalStateException> { TraceReplay.verifyCaptured(bad,replay) }
    }
    @Test fun sdkCacheAndUnverifiedNativeHeadingAreNotFreshTrajectoryEvidence() {
        val base=DrivingSession(0,3_000,DataOrigin.LIVE,telemetry=listOf(
            SensorReading("FusedLocation.lastKnown",1_000,ReadingKind.SNAPSHOT,fields=mapOf("value.Latitude" to "37","value.Longitude" to "127","value.Heading" to "90"))))
        assertTrue(PracticeInputs.normalize(base).positions.isEmpty())
        val row=base.telemetry.single().copy(signal="NaviHelper.onCurrentLocationInfo",kind=ReadingKind.UPDATE)
        val normalized=PracticeInputs.normalize(base.copy(telemetry=listOf(row,row.copy(atMs=2_000))))
        assertTrue(normalized.positions.all { it.value?.headingDeg==null && it.value?.accuracyM==null })
    }
    @Test fun liveMapExpiryIsNotRevivedAtLoadTimeAndFutureMapsAreRejected() {
        val network=PracticeCodec.encodeNetwork(session().roadNetwork!!.copy(provenance=RoadProvenance.VERIFIED_MAP))
        val expired=network + mapOf("effectiveFromEpochMs" to 1_000,"expiresAtEpochMs" to 5_000)
        val decoded=PracticeCodec.liveNetwork(JsonDocuments.write(expired),10_000,12_000,8_000)
        assertEquals(3_000L,decoded.segments.first().validUntilMs)
        assertFailsWith<IllegalArgumentException> { PracticeCodec.liveNetwork(JsonDocuments.write(expired + ("effectiveFromEpochMs" to 9_000)),10_000,12_000,8_000) }
    }
    @Test fun positionUncertaintyPreventsChoosingBetweenNearbyParallelRoads() {
        val s=PracticeScenarios.session("mapped-speed-excess")
        val first=s.positions.first().copy(value=s.positions.first().value!!.copy(accuracyM=10.0))
        val road=s.roadNetwork!!.segments.single()
        val parallel=road.copy(id="parallel",geometry=road.geometry.map { it.copy(longitude=it.longitude+0.0001) })
        assertEquals(MatchStatus.AMBIGUOUS,RoadMatcher().match(s.copy(roadNetwork=s.roadNetwork.copy(segments=listOf(road,parallel))),first).status)
    }
    @Test fun alreadyConfirmedCallerTurnsAreNotCountedTwice() {
        val s=session()
        val turn=engine.evaluate(s).practice.confirmedTurns.single()
        val report=engine.evaluate(s.copy(turns=listOf(turn.copy(id="external-confirmed"))))
        assertEquals(1,report.turns.opportunities)
        assertEquals(1,report.events.count { it.kind==EventKind.MISSING_TURN_SIGNAL })
    }
    @Test fun oneSdkPositionExplainsMissingHistoryHeadingAndAccuracy() {
        val s=DrivingSession(0,3_000,DataOrigin.LIVE,positions=listOf(Observation(1_000,PositionFix(GeoPoint(37.0,127.0),null,null,"SDK"))),positionTimeBasis=SteeringTimeBasis.CALLBACK_RECEIPT)
        val quality=engine.evaluate(s).practice.quality.single { it.channel=="trajectory" }
        assertEquals(0L,quality.eligibleMs)
        assertTrue(quality.reasons.containsAll(setOf("INSUFFICIENT_POSITION_HISTORY","HEADING_UNAVAILABLE","POSITION_ACCURACY_UNKNOWN")))
    }
    @Test fun signalQualityExplainsStaleWindowsSeparatelyFromTurnDetection() {
        val s=DrivingSession(0,3_000,DataOrigin.SYNTHETIC,signals=listOf(Observation(0,SignalSelection.OFF),Observation(1_500,SignalSelection.OFF)),signalSemantics=SignalSemantics.SELECTION_STATE)
        val quality=engine.evaluate(s).practice.quality.single { it.channel=="turnSignalSelection" }
        assertEquals(2_000L,quality.eligibleMs)
        assertEquals(setOf("STALE"),quality.reasons)
    }
    @Test fun roadLimitIsInvalidatedAtExactPositionGapBoundary() {
        val s=PracticeScenarios.session("mapped-speed-excess").copy(positionGaps=listOf(TimeRange(4_125,7_375)))
        val report=engine.evaluate(s)
        assertEquals(8_625L,report.speed.eligibleMs)
        assertTrue(report.events.none { it.kind==EventKind.SUSTAINED_SPEED_EXCESS && it.startMs < 7_375 && it.endMs > 4_125 })
    }
    @Test fun stuckPositionCannotKeepAnOldRoadLimitAliveWhileMoving() {
        val s=PracticeScenarios.session("mapped-speed-excess")
        val report=engine.evaluate(s.copy(positions=s.positions.map { it.copy(value=s.positions.first().value) }))
        assertTrue(report.speed.eligibleMs <= 500)
        assertTrue(report.events.none { it.kind==EventKind.SUSTAINED_SPEED_EXCESS })
    }
}
