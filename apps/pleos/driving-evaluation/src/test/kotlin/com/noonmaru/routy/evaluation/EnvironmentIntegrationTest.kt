package com.noonmaru.routy.evaluation

import org.junit.Test
import kotlin.test.*

class EnvironmentIntegrationTest {
    private fun session(uncertainty: Long=10, speed: Double=10.0): DrivingSession {
        val times=(1000L..3000L step 100).toList()
        return DrivingSession(1000,3100,DataOrigin.LIVE,
            speeds=times.map { Observation(it,speed) },
            environment=times.mapIndexed { index,t -> AlignedEnvironmentFrame(
                EnvironmentFrame("run",index.toLong(),t-1000,0.0,0.0,0.0,10.0,"OFF",emptyList(),"RED",100.0),t,uncertainty,500+t) })
    }
    @Test fun clockMappingUsesMonotonicMidpointAndRttBound() {
        assertEquals(EnvironmentClockMapping(910,20),EnvironmentClockMapping.probe(1000,1020,100))
        assertFails { EnvironmentClockMapping.probe(1020,1000,100) }
    }
    @Test fun integratedReportAndCompleteTraceRoundTrip() {
        val input=session()
        val report=DrivingEvaluationEngine().evaluate(input)
        assertEquals("0.7.0",report.engineVersion)
        assertNotNull(report.environment)
        assertTrue(report.assessments.first { it.category==Category.LANE_KEEPING }.eligibleMs>0)
        val document=JsonDocuments.read(JsonDocuments.write(TraceReplay.recording(input,report)))
        val restored=TraceReplay.session(document)
        assertEquals(input.environment,restored.environment)
        TraceReplay.verifyCaptured(document,DrivingEvaluationEngine().evaluate(restored))
    }
    @Test fun uncertaintyAndSdkContradictionsCannotBecomeEvaluatedCoverage() {
        for(input in listOf(session(101),session(speed=0.0))) {
            val report=DrivingEvaluationEngine().evaluate(input)
            assertEquals(0,report.assessments.first { it.category==Category.LANE_KEEPING }.eligibleMs)
            assertTrue(report.environment!!.events.isEmpty())
        }
    }
    @Test fun duplicatesCannotExtendCoverage() {
        val input=session()
        val report=DrivingEvaluationEngine().evaluate(input.copy(environment=input.environment.map { it.copy(frame=it.frame.copy(sequence=1)) }))
        assertEquals(0,report.assessments.first { it.category==Category.LANE_KEEPING }.eligibleMs)
    }
    @Test fun missingSdkSampleCannotResurrectEarlierSpeed() {
        val input=session()
        val first=input.speeds.first()
        val missing=Observation<Double>(first.atMs+1,null,false)
        val frames=EnvironmentIntegration.frames(input.copy(speeds=listOf(first,missing)))
        assertTrue(frames.drop(1).none { it.available })
    }
    @Test fun legacyEnvironmentRulesArePreservedInCapturedReplay() {
        val input=session().copy(environmentRuleVersion="environment-0.1")
        val engine=DrivingEvaluationEngine(); val report=engine.evaluate(input)
        assertEquals("0.5.0",report.engineVersion)
        val document=JsonDocuments.read(JsonDocuments.write(TraceReplay.recording(input,report)))
        val restored=TraceReplay.session(document)
        assertEquals("environment-0.1",restored.environmentRuleVersion)
        TraceReplay.verifyCaptured(document,engine.evaluate(restored))
    }
}
