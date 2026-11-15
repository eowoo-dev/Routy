package org.eowoo.routy.evaluation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DrivingEvaluationEngineTest {
    private val rawMotionConfig = EvaluationConfig(accelerationSmoothingMs = 0)

    @Test
    fun absentObservationsAreUnassessedRatherThanPerfectOrZeroScores() {
        val report = evaluate(DrivingSession(0, 8_000, DataOrigin.SYNTHETIC))

        assertEquals(AssessmentStatus.NOT_ASSESSED, report.assessment(Category.SPEED_MANAGEMENT).status)
        assertEquals(AssessmentStatus.NOT_ASSESSED, report.assessment(Category.ACCELERATION_CONTROL).status)
        assertEquals(AssessmentStatus.NOT_ASSESSED, report.assessment(Category.TURN_SIGNAL_USE).status)
        assertTrue(ExclusionReason.TURN_DETECTION_INCOMPLETE in report.assessment(Category.TURN_SIGNAL_USE).reasons)
        assertNull(report.speed.excessiveTimeRatio)
        assertNull(report.motion.meanAbsoluteAccelerationMps2)
        assertTrue(report.events.isEmpty())
    }

    @Test
    fun constantSpeedProducesKnownExposureAndNoMotionReviewEvents() {
        val report = evaluate(session())

        assertEquals(8_000L, report.speed.eligibleMs)
        assertEquals(80.0, report.speed.eligibleDistanceM, 1e-6)
        assertEquals(0.0, report.speed.excessiveTimeRatio)
        assertEquals(0.0, assertNotNull(report.motion.meanAbsoluteAccelerationMps2), 1e-6)
        assertEquals(ExclusionReason.JERK_NOT_CALIBRATED, report.motion.jerkExclusion)
        assertTrue(report.events.isEmpty())
    }

    @Test
    fun speedExcessAtTheMinimumSustainedDurationIsReported() {
        val report = evaluate(session(duration = 2_000, speed = { 13.0 }, limit = 10.0))

        val event = report.events.single { it.kind == EventKind.SUSTAINED_SPEED_EXCESS }
        assertEquals(0L, event.startMs)
        assertEquals(2_000L, event.endMs)
        assertEquals(2_000L, report.speed.excessiveMs)
        assertTrue(event.reviewOnly)
    }

    @Test
    fun exactExcessThresholdDoesNotCountAsExcess() {
        val config = rawMotionConfig.copy(speedExcessMps = 1.0, speedHysteresisMps = 0.25)
        val report = DrivingEvaluationEngine(config).evaluate(session(speed = { 11.0 }, limit = 10.0))

        assertEquals(0L, report.speed.excessiveMs)
        assertTrue(report.events.none { it.kind == EventKind.SUSTAINED_SPEED_EXCESS })
    }

    @Test
    fun shortExcessRemainsARecordedMetricWithoutASustainedEvent() {
        val report = evaluate(session(duration = 4_000, speed = { if (it < 1_000) 13.0 else 10.0 }, limit = 10.0))

        assertEquals(1_000L, report.speed.excessiveMs)
        assertTrue(report.events.none { it.kind == EventKind.SUSTAINED_SPEED_EXCESS })
    }

    @Test
    fun hysteresisKeepsOneActiveEventUntilTheLowerExitThreshold() {
        val report = evaluate(session(duration = 4_000, limit = 10.0, speed = {
            when {
                it < 1_000 -> 12.0
                it < 3_000 -> 11.2
                else -> 10.0
            }
        }))

        val event = report.events.single { it.kind == EventKind.SUSTAINED_SPEED_EXCESS }
        assertEquals(0L, event.startMs)
        assertEquals(3_000L, event.endMs)
    }

    @Test
    fun missingSpeedSeparatesShortBurstsInsteadOfJoiningThem() {
        val input = session(duration = 6_000, limit = 10.0).copy(
            speeds = (0L..6_000 step 250).map { at ->
                Observation(at, when {
                    at < 1_500 || at in 3_000L until 4_500L -> 13.0
                    at in 1_500L until 3_000L -> null
                    else -> 10.0
                })
            },
        )
        val report = evaluate(input)

        assertTrue(report.events.none { it.kind == EventKind.SUSTAINED_SPEED_EXCESS })
        assertTrue(report.assessment(Category.SPEED_MANAGEMENT).excludedMs > 0)
    }

    @Test
    fun explicitAcquisitionGapExcludesExposureAndSeparatesOtherwiseContinuousExcess() {
        val input = session(duration = 4_500, speed = { 13.0 }, limit = 10.0).copy(
            speedGaps = listOf(TimeRange(1_500, 3_000)),
        )
        val report = evaluate(input)

        assertEquals(3_000L, report.speed.eligibleMs)
        assertEquals(1_500L, report.assessment(Category.SPEED_MANAGEMENT).excludedMs)
        assertTrue(report.events.none { it.kind == EventKind.SUSTAINED_SPEED_EXCESS })
    }

    @Test
    fun explicitGapBetweenAvailableEndpointsStillPreventsDifferentiation() {
        val input = session(duration = 250).copy(
            speeds = listOf(Observation(0L, 0.0), Observation(250L, 0.75)),
            speedGaps = listOf(TimeRange(100, 150)),
        )
        val report = evaluate(input)

        assertEquals(0L, report.motion.eligibleMs)
        assertNull(report.motion.meanAbsoluteAccelerationMps2)
        assertTrue(report.events.none { it.kind in motionKinds })
    }

    @Test
    fun gapEndDoesNotRevivePreviousSpeedWithoutNewObservation() {
        val input = session(duration = 3_000).copy(
            speeds = listOf(Observation(0L, 10.0), Observation(2_000L, 10.0)),
            speedGaps = listOf(TimeRange(500, 1_000)),
        )
        val report = DrivingEvaluationEngine(rawMotionConfig.copy(speedMaxAgeMs = 3_000)).evaluate(input)

        assertEquals(1_500L, report.speed.eligibleMs)
        assertEquals(1_500L, report.assessment(Category.SPEED_MANAGEMENT).excludedMs)
        assertEquals(15.0, report.speed.eligibleDistanceM, 1e-6)
    }

    @Test
    fun roadTransitionSeparatesSpeedEventsEvenWithTheSameLimit() {
        val input = session(duration = 3_000, speed = { 13.0 }, limit = 10.0).copy(
            roads = listOf(
                Observation(0L, RoadContext("road-a", "road-a", 10.0, 3_000)),
                Observation(1_500L, RoadContext("road-b", "road-b", 10.0, 3_000)),
            ),
        )
        val report = evaluate(input)

        assertEquals(3_000L, report.speed.eligibleMs)
        assertTrue(report.events.none { it.kind == EventKind.SUSTAINED_SPEED_EXCESS })
    }

    @Test
    fun speedLimitFromAnotherRoadIsNotUsedToJudgeSpeed() {
        val input = session(duration = 2_000, speed = { 20.0 }).copy(
            roads = listOf(Observation(0L, RoadContext("road-a", "road-b", 10.0, 2_000))),
        )
        val report = evaluate(input)

        assertEquals(0L, report.speed.eligibleMs)
        assertTrue(ExclusionReason.ROAD_MISMATCH in report.assessment(Category.SPEED_MANAGEMENT).reasons)
        assertTrue(report.events.none { it.kind == EventKind.SUSTAINED_SPEED_EXCESS })
    }

    @Test
    fun explicitRoadValidityEndsEvenWhenObservationAgeWouldPermitHoldingIt() {
        val input = session(duration = 4_000).copy(
            roads = listOf(Observation(0L, RoadContext("road-a", "road-a", 15.0, 1_000))),
        )
        val report = DrivingEvaluationEngine(rawMotionConfig.copy(roadMaxAgeMs = 10_000)).evaluate(input)

        assertEquals(1_000L, report.speed.eligibleMs)
        assertEquals(3_000L, report.assessment(Category.SPEED_MANAGEMENT).excludedMs)
    }

    @Test
    fun staleSpeedIsNotHeldAcrossAnEntireRecordingGap() {
        val input = session(duration = 5_000).copy(
            speeds = listOf(Observation(0L, 10.0), Observation(4_000L, 10.0)),
        )
        val report = evaluate(input)

        assertEquals(2_000L, report.speed.eligibleMs)
        assertEquals(20.0, report.speed.eligibleDistanceM, 1e-6)
        assertTrue(ExclusionReason.STALE in report.assessment(Category.SPEED_MANAGEMENT).reasons)
        assertEquals(0L, report.motion.eligibleMs)
    }

    @Test
    fun duplicateOrDecreasingTimestampsInvalidateTheWholeSpeedStream() {
        val streams = listOf(
            listOf(Observation(0L, 10.0), Observation(0L, 11.0)),
            listOf(Observation(500L, 10.0), Observation(250L, 11.0)),
        )
        for (speeds in streams) {
            val report = evaluate(session().copy(speeds = speeds))
            assertEquals(0L, report.speed.eligibleMs)
            assertEquals(0L, report.motion.eligibleMs)
            assertTrue(ExclusionReason.INVALID_TIMELINE in report.assessment(Category.SPEED_MANAGEMENT).reasons)
        }
    }

    @Test
    fun aSampleOutsideTheSessionInvalidatesTheWholeSpeedStream() {
        for (at in listOf(-1L, 8_001L)) {
            val report = evaluate(session().copy(speeds = listOf(Observation(at, 10.0))))
            assertEquals(0L, report.speed.eligibleMs)
            assertEquals(0L, report.motion.eligibleMs)
            assertTrue(ExclusionReason.OUTSIDE_SESSION in report.assessment(Category.SPEED_MANAGEMENT).reasons)
        }
    }

    @Test
    fun unavailableAndInvalidSpeedValuesNeverBecomeZeroOrValidExposure() {
        val observations = listOf(
            Observation<Double>(0, null),
            Observation(0L, 10.0, available = false),
            Observation(0L, Double.NaN),
            Observation(0L, Double.POSITIVE_INFINITY),
            Observation(0L, -1.0),
            Observation(0L, 101.0),
        )
        for (observation in observations) {
            val report = evaluate(session(duration = 1_000).copy(speeds = listOf(observation)))
            assertEquals(0L, report.speed.eligibleMs, "Unexpected exposure for $observation")
            assertNull(report.speed.excessiveTimeRatio)
            assertTrue(report.events.isEmpty())
            if (observation.value == -1.0) {
                assertTrue(ExclusionReason.REVERSE_UNSUPPORTED in report.assessment(Category.SPEED_MANAGEMENT).reasons)
            }
        }
    }

    @Test
    fun knownAccelerationAndDecelerationProduceReviewEventsWithoutFaultJudgments() {
        val inputs = listOf(
            session(duration = 2_000, limit = 50.0, speed = { 2.0 + 3.0 * it / 1_000 }) to EventKind.RAPID_ACCELERATION,
            session(duration = 2_000, limit = 50.0, speed = { 20.0 - 4.0 * it / 1_000 }) to EventKind.RAPID_DECELERATION,
        )
        for ((input, expectedKind) in inputs) {
            val report = evaluate(input)
            val event = report.events.single { it.kind == expectedKind }
            assertTrue(event.endMs - event.startMs >= rawMotionConfig.sustainedAccelerationMs)
            assertTrue(event.reviewOnly)
            assertEquals(ExclusionReason.JERK_NOT_CALIBRATED, report.motion.jerkExclusion)
        }
    }

    @Test
    fun unsupportedDerivativeCadenceDoesNotCreateMotionJudgments() {
        for (step in listOf(20L, 1_000L)) {
            val input = session(duration = 2_000).copy(
                speeds = (0L..2_000 step step).map { Observation(it, 3.0 * it / 1_000) },
            )
            val report = evaluate(input)
            assertEquals(0L, report.motion.eligibleMs)
            assertNull(report.motion.meanAbsoluteAccelerationMps2)
            assertTrue(ExclusionReason.SAMPLE_INTERVAL_UNSUITABLE in report.assessment(Category.ACCELERATION_CONTROL).reasons)
            assertTrue(report.events.none { it.kind in motionKinds })
        }
    }

    @Test
    fun implausibleSpeedJumpIsExcludedInsteadOfBecomingRapidAcceleration() {
        val input = session(duration = 500).copy(
            speeds = listOf(Observation(0L, 0.0), Observation(250L, 10.0)),
        )
        val report = evaluate(input)

        assertEquals(0L, report.motion.eligibleMs)
        assertTrue(ExclusionReason.IMPLAUSIBLE_SPEED_CHANGE in report.assessment(Category.ACCELERATION_CONTROL).reasons)
        assertTrue(report.events.none { it.kind in motionKinds })
    }

    @Test
    fun confirmedTurnWithContinuousCorrectSelectionIsAdequatelySignalled() {
        val report = evaluate(turnSession { SignalSelection.LEFT })

        assertEquals(TurnMetrics(1, 1, 1), report.turns)
        assertEquals(AssessmentStatus.ASSESSED, report.assessment(Category.TURN_SIGNAL_USE).status)
        assertTrue(report.events.none { it.kind in turnKinds })
    }

    @Test
    fun zeroTurnsAreNoOpportunityOnlyWhenDetectionCoveredTheSession() {
        val report = evaluate(DrivingSession(
            startMs = 0,
            endMs = 8_000,
            origin = DataOrigin.SYNTHETIC,
            turnDetectionComplete = true,
        ))

        assertEquals(AssessmentStatus.NO_OPPORTUNITY, report.assessment(Category.TURN_SIGNAL_USE).status)
        assertEquals(TurnMetrics(0, 0, 0), report.turns)
        assertFalse(ExclusionReason.TURN_DETECTION_INCOMPLETE in report.assessment(Category.TURN_SIGNAL_USE).reasons)
    }

    @Test
    fun knownTurnsCanBeEvaluatedWhileIncompleteDetectionKeepsOverallCoveragePartial() {
        val input = turnSession { SignalSelection.LEFT }.copy(turnDetectionComplete = false)
        val report = evaluate(input)

        assertEquals(TurnMetrics(1, 1, 1), report.turns)
        assertEquals(AssessmentStatus.PARTIAL, report.assessment(Category.TURN_SIGNAL_USE).status)
        assertTrue(ExclusionReason.TURN_DETECTION_INCOMPLETE in report.assessment(Category.TURN_SIGNAL_USE).reasons)
        assertTrue(report.events.none { it.kind in turnKinds })
    }

    @Test
    fun missingOppositeLateAndInterruptedSelectionsRemainDistinct() {
        val cases: List<Pair<(Long) -> SignalSelection, EventKind>> = listOf(
            { _: Long -> SignalSelection.OFF } to EventKind.MISSING_TURN_SIGNAL,
            { _: Long -> SignalSelection.RIGHT } to EventKind.OPPOSITE_TURN_SIGNAL,
            { at: Long -> if (at < 3_000) SignalSelection.OFF else SignalSelection.LEFT } to EventKind.LATE_TURN_SIGNAL,
            { at: Long -> if (at < 3_500) SignalSelection.LEFT else SignalSelection.OFF } to EventKind.INTERRUPTED_TURN_SIGNAL,
        )
        for ((selection, expectedKind) in cases) {
            val report = evaluate(turnSession(selection))
            assertEquals(TurnMetrics(1, 1, 0), report.turns)
            assertEquals(expectedKind, report.events.single { it.kind in turnKinds }.kind)
        }
    }

    @Test
    fun navigationOnlyAndIncompleteManeuversAreNotScoredAsActualTurns() {
        val base = turnSession { SignalSelection.OFF }
        val unconfirmed = base.copy(turns = listOf(turn().copy(evidence = setOf(TurnEvidence.NAVIGATION_INSTRUCTION))))
        val incomplete = base.copy(turns = listOf(turn().copy(endMs = base.endMs + 1)))

        for ((input, reason) in listOf(
            unconfirmed to ExclusionReason.UNCONFIRMED_TURN,
            incomplete to ExclusionReason.INCOMPLETE_MANEUVER,
        )) {
            val report = evaluate(input)
            assertEquals(0, report.turns.evaluated)
            assertTrue(reason in report.assessment(Category.TURN_SIGNAL_USE).reasons)
            assertTrue(report.events.none { it.kind in turnKinds })
        }
    }

    @Test
    fun unknownOrBlinkLampSemanticsCannotProveSignalMisuse() {
        for (semantics in listOf(SignalSemantics.UNKNOWN, SignalSemantics.BLINK_LAMP)) {
            val report = evaluate(turnSession { SignalSelection.OFF }.copy(signalSemantics = semantics))
            assertEquals(0, report.turns.evaluated)
            assertTrue(ExclusionReason.UNKNOWN_SIGNAL_SEMANTICS in report.assessment(Category.TURN_SIGNAL_USE).reasons)
            assertTrue(report.events.none { it.kind in turnKinds })
        }
    }

    @Test
    fun hazardSelectionAndSignalCoverageGapsHoldTheTurnAssessment() {
        val hazard = turnSession { if (it == 2_500L) SignalSelection.HAZARD else SignalSelection.LEFT }
        val missingStart = turnSession { SignalSelection.OFF }.let { input ->
            input.copy(signals = input.signals.filter { it.atMs >= 1_500 })
        }
        val missingMiddle = turnSession { SignalSelection.OFF }.let { input ->
            input.copy(signals = input.signals.map { if (it.atMs == 2_500L) it.copy(value = null) else it })
        }
        for (input in listOf(hazard, missingStart, missingMiddle)) {
            val report = evaluate(input)
            assertEquals(0, report.turns.evaluated)
            assertTrue(report.events.none { it.kind in turnKinds })
        }
        assertTrue(ExclusionReason.HAZARD_CONTEXT_UNKNOWN in evaluate(hazard).assessment(Category.TURN_SIGNAL_USE).reasons)
    }

    @Test
    fun turnTooNearSessionStartCannotBeJudgedWithoutTheFullLeadWindow() {
        val input = turnSession { SignalSelection.OFF }.copy(
            turns = listOf(turn().copy(startMs = 1_000, endMs = 2_000)),
        )
        val report = evaluate(input)

        assertEquals(0, report.turns.evaluated)
        assertTrue(ExclusionReason.INSUFFICIENT_LOOKBACK in report.assessment(Category.TURN_SIGNAL_USE).reasons)
        assertTrue(report.events.none { it.kind in turnKinds })
    }

    @Test
    fun reportsRemainDeterministicAcrossReplaysAndSeparateSessions() {
        val input = turnSession { SignalSelection.OFF }
        val engine = DrivingEvaluationEngine(rawMotionConfig)
        val first = engine.evaluate(input)
        val empty = engine.evaluate(DrivingSession(0, 8_000, DataOrigin.SYNTHETIC))

        assertTrue(empty.events.isEmpty())
        assertEquals(first, engine.evaluate(input))
        assertEquals(first, DrivingEvaluationEngine(rawMotionConfig).evaluate(input))
        assertEquals(DataOrigin.SYNTHETIC, first.origin)
        assertEquals(rawMotionConfig, first.config)
    }

    @Test
    fun environmentalCategoriesStayExplicitlyUnassessedWithOnlyEgoSignals() {
        val report = evaluate(turnSession { SignalSelection.LEFT })

        for (category in listOf(Category.LANE_KEEPING, Category.TRAFFIC_SIGNAL_RESPONSE, Category.FOLLOWING_DISTANCE)) {
            val assessment = report.assessment(category)
            assertEquals(AssessmentStatus.NOT_ASSESSED, assessment.status)
            assertTrue(ExclusionReason.ENVIRONMENT_DATA_REQUIRED in assessment.reasons)
            assertEquals(0, assessment.evaluatedOpportunities)
        }
        assertFalse(report.events.any { !it.reviewOnly })
    }

    private fun evaluate(input: DrivingSession): EvaluationReport = DrivingEvaluationEngine(rawMotionConfig).evaluate(input)

    private fun EvaluationReport.assessment(category: Category): Assessment = assessments.single { it.category == category }

    private fun session(
        duration: Long = 8_000,
        limit: Double = 15.0,
        speed: (Long) -> Double = { 10.0 },
    ): DrivingSession = DrivingSession(
        startMs = 0,
        endMs = duration,
        origin = DataOrigin.SYNTHETIC,
        turnDetectionComplete = true,
        speeds = (0L..duration step 250).map { Observation(it, speed(it)) },
        roads = (0L..duration step 1_000).map {
            Observation(it, RoadContext("road-a", "road-a", limit, duration))
        },
    )

    private fun turnSession(selection: (Long) -> SignalSelection): DrivingSession = session(duration = 7_000).copy(
        signals = (0L..7_000 step 500).map { Observation(it, selection(it)) },
        signalSemantics = SignalSemantics.SELECTION_STATE,
        turns = listOf(turn()),
    )

    private fun turn(): TurnManeuver = TurnManeuver(
        id = "left-turn-1",
        startMs = 4_000,
        endMs = 5_000,
        direction = TurnDirection.LEFT,
        evidence = setOf(TurnEvidence.MATCHED_ROAD_TRANSITION, TurnEvidence.OBSERVED_HEADING_CHANGE),
    )

    private val motionKinds = setOf(EventKind.RAPID_ACCELERATION, EventKind.RAPID_DECELERATION)
    private val turnKinds = setOf(
        EventKind.MISSING_TURN_SIGNAL,
        EventKind.OPPOSITE_TURN_SIGNAL,
        EventKind.LATE_TURN_SIGNAL,
        EventKind.INTERRUPTED_TURN_SIGNAL,
    )
}
