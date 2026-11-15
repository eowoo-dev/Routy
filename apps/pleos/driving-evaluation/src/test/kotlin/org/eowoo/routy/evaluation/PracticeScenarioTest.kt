package org.eowoo.routy.evaluation

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.*

@RunWith(Parameterized::class)
class PracticeScenarioTest(private val scenario: PracticeScenarios.Case) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name="{0}")
        fun cases(): Collection<Array<Any>> = PracticeScenarios.cases().map { arrayOf<Any>(it) }
    }
    @Test fun derivesBehaviorAndLearningFeedbackFromIndependentInputs() {
        val session=PracticeScenarios.session(scenario.id)
        assertTrue(session.turns.isEmpty(),"No confirmed maneuver may be pre-injected")
        assertTrue(session.roads.isEmpty(),"Road contexts must come from map matching")
        val engine=DrivingEvaluationEngine()
        val report=engine.evaluate(session)
        assertTrue(scenario.violations(report).isEmpty(),scenario.violations(report).joinToString("\n"))
        assertEquals(report,engine.evaluate(session))
    }
}
