package org.eowoo.routy.evaluation

import kotlin.test.*

class SensorFusionTest {
    private val event = EvaluationEvent(EventKind.REPEATED_STEERING_REVERSALS, 1_000, 2_000, emptyMap())
    private fun observation(key: String, state: String? = null, flag: Boolean? = null, at: Long = 1_400, available: Boolean = true) =
        SensorReading(key, at, ReadingKind.SNAPSHOT, state = state, flag = flag, available = available)
    private val gearKey = "AAOS.GEAR_SELECTION"
    private val turnKey = "Vehicle.TurnSignal.getCurrentTurnSignalState"
    private val brakeKey = "Vehicle.Brake.getCurrentBrakePedalStatus"
    private fun session(speed: Double = 10.0, telemetry: List<SensorReading> = emptyList()) =
        DrivingSession(0, 5_000, DataOrigin.SYNTHETIC, speeds = listOf(Observation(1_400, speed)), telemetry = telemetry)
    private fun context(s: DrivingSession) = fuseContext(s, listOf(event)).single()

    @Test fun closedReadPolicyRejectsControlsPrivateAndIdentifiers() {
        assertEquals(19, SensorReadPolicy.domains.size)
        for (method in listOf("setCurrentTurnSignalState", "getInternalApi", "getVINInfo", "getCRNInfo", "moveSeat", "getAndClear", "getCapabilityInfo"))
            assertFalse(SensorReadPolicy.allowed("CarInfo", method), method)
        assertFalse(SensorReadPolicy.allowed("Safety", "getTelltalesInfo"))
        assertFalse(SensorReadPolicy.allowed("UnknownDomain", "getState"))
        assertTrue(SensorReadPolicy.allowed("TurnSignal", "getCurrentTurnSignalState"))
        assertEquals(SensorReadPolicy.feature("getCurrentTurnSignalState"), SensorReadPolicy.feature("checkTurnSignalStateCapabilities"))
    }
    @Test fun documentedCapabilityAndListenerAliasesMatch() {
        for ((read, cap) in listOf("hasSeatAirbagBeenDeployed" to "checkAirbagDeployedCapabilities",
            "hasSeatAirbagEnabled" to "checkAirbagCapabilities", "hasSeatBeltNotBuckledWarning" to "checkSeatNotBuckledWarningCapabilities",
            "getHvacSeatVentilationState" to "checkSeatVentilationCapabilities")) {
            assertEquals(SensorReadPolicy.feature(read), SensorReadPolicy.feature(cap))
        }
    }
    @Test fun stationaryAndReverseExplainSteeringWithoutFaultFinding() {
        assertEquals(FusionContext.STATIONARY_MANEUVER, context(session(0.0)).context)
        assertEquals(FusionContext.REVERSE_MANEUVER, context(session(telemetry = listOf(observation(gearKey, "REVERSE")))).context)
        assertFalse(context(session()).lanePositionKnown)
    }
    @Test fun movingInParkIsContradictoryNotAConfidentClassification() {
        assertEquals(FusionContext.INCONSISTENT_INPUTS, context(session(telemetry = listOf(observation(gearKey, "PARK")))).context)
    }
    @Test fun indicatorBrakeAndCruiseAreContextNotProofOfLaneKeeping() {
        assertEquals(FusionContext.SIGNALLED_STEERING, context(session(telemetry = listOf(observation(turnKey, "LEFT")))).context)
        assertEquals(FusionContext.BRAKING_MANEUVER, context(session(telemetry = listOf(observation(brakeKey, flag = true)))).context)
        val cruise = observation("Vehicle.DrivingMode.getCruiseControlState", "ACTIVATED")
        assertEquals(FusionContext.CRUISE_CONTROL_ACTIVE, context(session(telemetry = listOf(cruise))).context)
    }
    @Test fun repeatedCorrectionsRequireJointEvidence() {
        val readings = listOf(observation(turnKey, "OFF"), observation(brakeKey, flag = false))
        assertEquals(FusionContext.UNSIGNALLED_CORRECTION_CANDIDATE, context(session(telemetry = readings)).context)
        assertEquals(FusionContext.UNRESOLVED, context(session(telemetry = readings.take(1))).context)
        assertEquals(FusionContext.UNRESOLVED, context(session(telemetry = readings).copy(telemetryTruncated = true)).context)
        val gap = observation(turnKey, at = 1_450, available = false).copy(kind = ReadingKind.GAP)
        val interrupted = session(telemetry = readings + gap)
        assertEquals(FusionContext.UNRESOLVED, context(interrupted).context)
        assertEquals(0, DrivingEvaluationEngine().evaluate(interrupted).telemetry.updates)
    }
    @Test fun futureStaleUnavailableAndConflictingValuesAreNotEvidence() {
        for (values in listOf(listOf(observation(turnKey, "LEFT", at = 1_501)),
            listOf(observation(turnKey, "LEFT", available = false)),
            listOf(observation(turnKey, "LEFT"), observation(turnKey, "RIGHT")),
            listOf(observation(turnKey, "LEFT"), observation(turnKey, "LEFT", available = false)))) {
            assertEquals(FusionContext.UNRESOLVED, context(session(telemetry = values)).context)
        }
        val later = event.copy(startMs = 4_000, endMs = 5_000)
        assertEquals(FusionContext.UNRESOLVED, fuseContext(session(telemetry = listOf(observation(turnKey, "LEFT"))), listOf(later)).single().context)
    }
    @Test fun invalidSpeedTimelineAndGapsCannotClaimStationary() {
        val s = session(0.0)
        for (input in listOf(s.copy(speeds = s.speeds + s.speeds),
            s.copy(speedGaps = listOf(TimeRange(1_450, 1_600))),
            s.copy(speeds = listOf(Observation(1_400, Double.NaN))),
            s.copy(speeds = listOf(Observation(1_400, -1.0))))) {
            assertEquals(FusionContext.UNRESOLVED, context(input).context)
            assertTrue("speedMps" in context(input).missing)
        }
    }
    @Test fun aGapRequiresNewSpeedRatherThanRevivingTheOldValue() {
        assertEquals(FusionContext.UNRESOLVED, context(session(0.0).copy(speedGaps = listOf(TimeRange(1_410, 1_450)))).context)
    }
}
