package com.noonmaru.routy.evaluation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LongRecordingTest {
    @Test fun twoHourRecordEvaluatesSamplesAndBrakingBeyondFormerCaps() {
        val end=7_200_000L
        val brakingAt=end-5_000
        val speeds=(0L..end step 100).map { at ->
            Observation(at,when { at<brakingAt->20.0;at<brakingAt+2_000->20.0-(at-brakingAt)*0.008;else->4.0 })
        }
        val angles=(0L..end step 100).map { Observation(it,0.0) }
        // More than the former telemetry ceiling, with evidence near the very end.
        val telemetry=(0L..end step 30).map { SensorReading("Vehicle.Brake.getCurrentBrakePedalStatus",it,ReadingKind.UPDATE,flag=it>=brakingAt) }
        assertTrue(speeds.size>20_000 && angles.size>20_000 && telemetry.size>200_000)
        val report=DrivingEvaluationEngine().evaluate(DrivingSession(0,end,DataOrigin.LIVE,
            speeds=speeds,steeringAngles=angles,steeringTimeBasis=SteeringTimeBasis.PROVIDER,telemetry=telemetry))
        assertEquals(end,report.endMs-report.startMs)
        assertEquals(end,report.motion.eligibleMs)
        assertEquals(end,report.steering.eligibleMs)
        assertEquals(telemetry.size,report.telemetry.readings)
        assertTrue(!report.telemetry.truncated)
        assertTrue(report.events.any { it.kind==EventKind.RAPID_DECELERATION && it.startMs>=brakingAt })
        assertTrue(report.contextFindings.any { it.startMs>=brakingAt && it.context==FusionContext.BRAKING_MANEUVER })
        val summary=JsonDocuments.read(JsonDocuments.write(TraceReplay.summary(report)))
        assertEquals(end.toDouble(),(summary["endMs"] as Number).toDouble())
    }
}
