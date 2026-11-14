package com.noonmaru.routy.evaluation

import kotlin.test.*
import org.junit.Test

class EnvironmentEvaluationTest {
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
