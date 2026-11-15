package org.eowoo.routy.evaluation

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class DemoScenarioTest(private val scenario: DemoScenario) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases(): Collection<Array<Any>> = DemoScenarios.cases().map { arrayOf<Any>(it) }
    }
    @Test fun matchesIndependentExpectedResultsAndReplaysDeterministically() {
        val engine = DrivingEvaluationEngine()
        val report = engine.evaluate(scenario.session())
        assertTrue(scenario.violations(report).isEmpty(), scenario.violations(report).joinToString("\n"))
        assertEquals(report, engine.evaluate(scenario.session()))
    }
}
