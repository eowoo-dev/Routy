package com.noonmaru.routy.evaluation

import kotlin.test.*

class TraceReplayTest {
    private fun document(): JsonObject = JsonDocuments.read("""{
      "engineVersion":"0.3.0","ruleVersion":"experimental-0.3","origin":"LIVE",
      "startMs":0,"endMs":1000,"truncated":false,"steeringTimeBasis":"CALLBACK_RECEIPT",
      "speed":[{"atMs":0,"value":null,"available":false},{"atMs":100,"value":5,"available":true}],
      "steering":[],"speedGaps":[],"steeringGaps":[],"availability":[],"contextFindings":[],
      "readings":[{"signal":"test.state","atMs":50,"kind":"GAP","number":null,"flag":null,
      "state":null,"fields":{"unit":"native"},"available":false,"providerTimestampMs":123}]
    }""")

    @Test fun preservesMissingValuesAndSeparateProviderTime() {
        val s = TraceReplay.session(document())
        assertEquals(DataOrigin.RECORDED, s.origin)
        assertNull(s.speeds.first().value)
        assertFalse(s.speeds.first().available)
        assertEquals(50L, s.telemetry.single().atMs)
        assertEquals(123L, s.telemetry.single().providerTimestampMs)
        assertEquals(ReadingKind.GAP, s.telemetry.single().kind)
        TraceReplay.verifyCaptured(document(), DrivingEvaluationEngine().evaluate(s))
    }
    @Test fun duplicateTimestampsAreNotSilentlySortedOrRemoved() {
        val d = document().toMutableMap()
        val samples = d.objects("speed")
        d["speed"] = listOf(samples.last(), samples.last())
        val s = TraceReplay.session(d)
        assertEquals(2, s.speeds.size)
        val assessment = DrivingEvaluationEngine().evaluate(s).assessments.single { it.category == Category.ACCELERATION_CONTROL }
        assertEquals(AssessmentStatus.NOT_ASSESSED, assessment.status)
        assertTrue(ExclusionReason.INVALID_TIMELINE in assessment.reasons)
    }
    @Test fun changedCapturedResultsFailVerification() {
        val d = document().toMutableMap()
        val report = DrivingEvaluationEngine().evaluate(TraceReplay.session(d))
        d["contextFindings"] = listOf(mapOf("event" to "RAPID_STEERING", "startMs" to 0, "endMs" to 100,
            "context" to "SIGNALLED_STEERING", "evidence" to emptyMap<String,String>(), "missing" to emptyList<String>(), "lanePositionKnown" to false))
        assertFailsWith<IllegalStateException> { TraceReplay.verifyCaptured(d, report) }
    }
    @Test fun incompatibleVersionsAndSyntheticTracesRequireExplicitHandling() {
        for ((key, value) in listOf("engineVersion" to "future", "ruleVersion" to "other", "origin" to "SYNTHETIC"))
            assertFailsWith<IllegalArgumentException> { TraceReplay.session(document() + (key to value)) }
    }
    @Test fun malformedAndFractionalTimesFailInsteadOfBeingRounded() {
        assertFails { TraceReplay.session(document() - "speed") }
        assertFailsWith<IllegalArgumentException> { TraceReplay.session(document() + ("startMs" to 0.5)) }
        assertFails { JsonDocuments.read("{broken") }
    }
}
