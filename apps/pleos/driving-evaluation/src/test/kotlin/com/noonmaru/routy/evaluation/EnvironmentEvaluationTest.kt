package com.noonmaru.routy.evaluation

import kotlin.test.*
import org.junit.Test

class EnvironmentEvaluationTest {
    private fun change(signal: (Long)->String = { "LEFT" }): List<EnvironmentFrame> = (0L..10000 step 50).map { t ->
        frame(t,d=when { t<4000 -> 0.0; t<6500 -> (t-4000)*3.7/2500; else -> 3.7 },signal=signal(t))
    }
    @Test fun offThenOnDuringChangeIsInterruptedNotAdequateOrLate() {
        val events=EnvironmentEvaluation.evaluate(change { if(it in 5500..5750) "OFF" else "LEFT" }).events.map { it.kind }
        assertTrue("INTERRUPTED_LANE_CHANGE_SIGNAL" in events)
        assertFalse("LANE_CHANGE_SIGNAL_OBSERVED" in events)
        assertFalse("LATE_LANE_CHANGE_SIGNAL" in events)
    }
    @Test fun signalInterruptionWhileTemporarilyStationaryIsAlsoRemembered() {
        val frames=change { if(it in 5500..5750) "OFF" else "LEFT" }.map {
            if(it.atMs in 5500..5750) it.copy(speed=0.0,d=2.22) else it
        }
        val kinds=EnvironmentEvaluation.evaluate(frames).events.map { it.kind }
        assertTrue("INTERRUPTED_LANE_CHANGE_SIGNAL" in kinds)
        assertFalse("LANE_CHANGE_SIGNAL_OBSERVED" in kinds)
    }
    @Test fun temporaryOppositeAndUnknownAreRememberedAfterRestoration() {
        for((state,expected) in listOf("RIGHT" to "OPPOSITE_LANE_CHANGE_SIGNAL","UNKNOWN" to "LANE_CHANGE_SIGNAL_UNAVAILABLE")) {
            val kinds=EnvironmentEvaluation.evaluate(change { if(it in 5500..5750) state else "LEFT" }).events.map { it.kind }
            assertTrue(expected in kinds);assertFalse("LANE_CHANGE_SIGNAL_OBSERVED" in kinds)
        }
    }
    @Test fun preparedSignalIsNotShortenedByCompletionTime() {
        val kinds=EnvironmentEvaluation.evaluate(change()).events.map { it.kind }
        assertTrue("LANE_CHANGE_SIGNAL_OBSERVED" in kinds)
    }
    @Test fun signalFirstUsedAfterCrossingIsLateRatherThanNeverUsed() {
        val kinds=EnvironmentEvaluation.evaluate(change { if(it<5500) "OFF" else "LEFT" }).events.map { it.kind }
        assertTrue("LATE_LANE_CHANGE_SIGNAL" in kinds);assertFalse("MISSING_LANE_CHANGE_SIGNAL" in kinds)
    }
    @Test fun completedChangeDoesNotBecomeLaneKeepingIntrusion() {
        assertFalse(EnvironmentEvaluation.evaluate(change()).events.any { it.kind=="LANE_BOUNDARY_INTRUSION" })
    }
    @Test fun multipleLanesAreNotOneOrdinaryChange() {
        val frames=change().map { f -> f.copy(d=f.d*2-3.7) }
        val kinds=EnvironmentEvaluation.evaluate(frames).events.map { it.kind }
        assertTrue("MULTI_LANE_CHANGE_LEFT" in kinds);assertFalse("LANE_CHANGE_LEFT" in kinds)
    }
    @Test fun leaderReplacementDoesNotMergeSeparateShortEpisodes() {
        val frames=(0L..700 step 50).map { t -> frame(t,vehicles=listOf(EnvironmentVehicle(if(t<350) "a" else "b",10.0,0.0,0.0))) }
        assertFalse(EnvironmentEvaluation.evaluate(frames).events.any { it.kind=="SHORT_FOLLOWING_HEADWAY" })
    }
    @Test fun overlapIsRecordedInsteadOfReturningSafeHeadway() {
        val events=EnvironmentEvaluation.evaluate((0L..600 step 50).map { frame(it,vehicles=listOf(EnvironmentVehicle("lead",3.0,0.0,0.0))) }).events
        assertTrue(events.any { it.kind=="VEHICLE_BODY_OVERLAP" })
        assertFalse(events.any { it.kind=="SHORT_FOLLOWING_HEADWAY" || it.kind=="LOW_CLOSING_TTC" })
    }
    @Test fun signalTransitionAtCrossingIsUncertain() {
        val result=EnvironmentEvaluation.evaluate(listOf(frame(0,s=97.0),frame(50,s=98.0).copy(light="GREEN")))
        assertEquals(listOf("SIGNAL_CROSSING_UNCERTAIN"),result.events.map { it.kind })
    }
    private fun frame(t: Long,d: Double=0.0,s: Double=0.0,speed: Double=10.0,signal: String="OFF",vehicles:List<EnvironmentVehicle> = emptyList()) =
        EnvironmentFrame("test",t/50,t,s,d,0.0,speed,signal,vehicles,"RED",100.0)
    @Test fun steadyHasNoEvents() { assertTrue(EnvironmentEvaluation.evaluate((0L..2000 step 50).map { frame(it) }).events.isEmpty()) }
    @Test fun leftChangeWithoutSignal() {
        val frames=(0L..4000 step 50).map { t -> frame(t,d=when { t<1000 -> 0.0; t<2500 -> (t-1000)*3.7/1500; else -> 3.7 }) }
        val kinds=EnvironmentEvaluation.evaluate(frames).events.map { it.kind }
        assertTrue("LANE_CHANGE_LEFT" in kinds); assertTrue("MISSING_LANE_CHANGE_SIGNAL" in kinds)
    }
    @Test fun gapCannotCompleteChange() {
        val frames=(0L..1000 step 50).map { frame(it) }+(3000L..5000 step 50).map { frame(it,d=3.7) }
        assertFalse(EnvironmentEvaluation.evaluate(frames).events.any { it.kind=="LANE_CHANGE_LEFT" })
    }
    @Test fun followingExcludesSideLane() {
        val side=listOf(EnvironmentVehicle("side",10.0,3.7,0.0))
        assertTrue(EnvironmentEvaluation.evaluate((0L..1000 step 50).map { frame(it,vehicles=side) }).events.isEmpty())
        val front=listOf(EnvironmentVehicle("lead",15.0,0.0,0.0))
        assertTrue(EnvironmentEvaluation.evaluate((0L..1000 step 50).map { frame(it,vehicles=front) }).events.any { it.kind=="LOW_CLOSING_TTC" })
    }
    @Test fun redCrossingUsesFrontBumper() {
        val result=EnvironmentEvaluation.evaluate(listOf(frame(0,s=97.0),frame(50,s=98.0)))
        assertTrue(result.events.any { it.kind=="STOP_LINE_CROSSING_RED" })
    }
    @Test fun boundaryApproachAndRedStop() {
        assertTrue(EnvironmentEvaluation.evaluate((0L..1000 step 50).map { frame(it,d=.8) }).events.any { it.kind=="LANE_BOUNDARY_APPROACH" })
        assertTrue(EnvironmentEvaluation.evaluate((0L..1000 step 50).map { frame(it,s=96.0,speed=0.0) }).events.any { it.kind=="STOPPED_BEFORE_RED" })
    }
}
