package org.eowoo.routy.evaluation

import kotlin.test.*
import org.junit.Test

class SubmissionScenariosTest {
    @Test fun bundledObservationsProduceExpectedJudgmentsAndSyntheticReplay() {
        val cases=SubmissionScenarios.all
        assertEquals(13,cases.size)
        for(c in cases) {
            val playback=SubmissionPlayback(c,"test-${c.id}")
            playback.start()
            while(playback.phase==SubmissionPlayback.Phase.RUNNING)playback.step()
            val frames=playback.completedFrames()
            val report=DrivingEvaluationEngine().evaluate(ScenarioReview.session(frames))
            assertEquals(DataOrigin.SYNTHETIC,report.origin,c.id)
            val result=checkNotNull(report.environment)
            assertEquals("SIMULATED_ENVIRONMENT",result.source)
            val kinds=result.events.map { it.kind }.toSet()
            assertTrue(kinds.containsAll(c.expected),"${c.id}: missing ${c.expected-kinds}")
            assertTrue(c.forbidden.none { it in kinds },"${c.id}: unexpected ${c.forbidden.intersect(kinds)}")
            assertEquals(0,result.excluded,c.id)
            val document=JsonDocuments.read(JsonDocuments.write(ScenarioReview.recording(frames,report)))
            assertEquals("SYNTHETIC",document["origin"])
            assertTrue(document.objects("frames").all { it["source"]=="SIMULATED_ENVIRONMENT" })
            TraceReplay.verifyCaptured(document,DrivingEvaluationEngine().evaluate(TraceReplay.session(document)))
        }
    }

    @Test fun stoppedRunsCannotBeSavedAndResumePreservesEveryFrame() {
        val playback=SubmissionPlayback(SubmissionScenarios.all.first(),"unique-run")
        assertFailsWith<IllegalStateException>{playback.completedFrames()}
        playback.start(); repeat(120){playback.step()};playback.pause()
        val paused=playback.elapsedMs
        repeat(100){playback.step()}
        assertEquals(paused,playback.elapsedMs)
        assertFailsWith<IllegalStateException>{playback.completedFrames()}
        playback.start()
        while(playback.phase==SubmissionPlayback.Phase.RUNNING)playback.step()
        assertEquals(playback.scenario.durationMs,playback.elapsedMs)
        val frames=playback.completedFrames()
        assertTrue(frames.all { it.runId=="unique-run" })
        assertEquals(frames.size,frames.map { it.sequence }.distinct().size)
        val reset=SubmissionPlayback(playback.scenario,"another-run")
        assertEquals(0L,reset.elapsedMs)
        assertEquals(SubmissionPlayback.Phase.READY,reset.phase)
        assertFailsWith<IllegalStateException>{reset.completedFrames()}
    }
}
