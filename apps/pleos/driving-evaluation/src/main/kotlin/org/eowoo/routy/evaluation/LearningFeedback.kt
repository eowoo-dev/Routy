package org.eowoo.routy.evaluation

/** Deterministic, evidence-linked post-drive coaching. Priorities are review order, not risk scores. */
class LearningFeedbackBuilder(private val config: PracticeConfig = PracticeConfig(), private val signalLeadMs: Long = 3_000) {
    fun build(session: DrivingSession, events: List<EvaluationEvent>, contexts: List<ContextFinding>,
        practice: PracticeAnalysis, assessments: List<Assessment>, turns: TurnMetrics): List<LearningFeedback> {
        data class Group(val key: String, val rows: MutableList<EvaluationEvent>, val context: String)
        fun context(event: EvaluationEvent): String {
            val raw = contexts.firstOrNull { it.event == event.kind && it.startMs == event.startMs && it.endMs == event.endMs }?.context
            if (raw in setOf(FusionContext.STATIONARY_MANEUVER,FusionContext.REVERSE_MANEUVER,FusionContext.CRUISE_CONTROL_ACTIVE,FusionContext.BRAKING_MANEUVER,FusionContext.INCONSISTENT_INPUTS)) return raw!!.name
            val at = event.startMs+(event.endMs-event.startMs)/2
            val maneuver = practice.maneuvers.firstOrNull { event.startMs >= it.startMs && event.endMs <= it.endMs && it.kind in setOf(ManeuverKind.CURVE,ManeuverKind.STRAIGHT,ManeuverKind.LOW_SPEED_MANEUVER,ManeuverKind.REVERSE_MANEUVER) }
            return maneuver?.kind?.name ?: raw?.name ?: "UNRESOLVED"
        }
        fun family(kind: EventKind) = when (kind) {
            EventKind.RAPID_STEERING,EventKind.REPEATED_STEERING_REVERSALS -> "STEERING"
            EventKind.MISSING_TURN_SIGNAL,EventKind.LATE_TURN_SIGNAL,EventKind.OPPOSITE_TURN_SIGNAL,EventKind.INTERRUPTED_TURN_SIGNAL -> "SIGNAL"
            EventKind.RAPID_ACCELERATION,EventKind.RAPID_DECELERATION -> "MOTION"
            else -> "SPEED"
        }
        val groups = mutableListOf<Group>()
        for (event in events) {
            val context = context(event)
            val key = "${family(event.kind)}:$context"
            val previous = groups.lastOrNull { it.key == key }
            if (previous != null && event.startMs-previous.rows.maxOf { it.endMs } <= config.reviewMergeGapMs)
                previous.rows += event
            else groups += Group(key,mutableListOf(event),context)
        }
        val feedback = groups.mapIndexed { index,group ->
            val kinds = group.rows.map { it.kind }.toSet()
            val sourceContexts = contexts.filter { c -> group.rows.any { it.kind == c.event && it.startMs == c.startMs && it.endMs == c.endMs } }
            val missing = sourceContexts.flatMap { it.missing }.toSet()
            val evidence = group.rows.flatMap { it.evidence.entries }.associate { it.key to it.value } + mapOf(
                "groupedEvents" to group.rows.size.toString(),"context" to group.context,
                "reviewBasis" to "post-drive observations; no driver-fault conclusion")
            var priority = ReviewPriority.REVIEW
            var strength = EvidenceStrength.LIMITED
            val message = when {
                EventKind.MISSING_TURN_SIGNAL in kinds -> {
                    priority=ReviewPriority.PRACTICE; strength=EvidenceStrength.SUPPORTED
                    Triple("확인된 회전 전에 방향지시등이 관측되지 않았어요","도로 전환과 진행 방향 변화로 확인된 회전의 준비 구간에서 방향지시등 선택이 OFF였어요.","다음 연습을 시작하기 전에 회전 준비 순서를 복습하고, 주행 후 신호를 준비한 시점을 확인해보세요.")
                }
                EventKind.LATE_TURN_SIGNAL in kinds -> {
                    priority=ReviewPriority.PRACTICE; strength=EvidenceStrength.SUPPORTED
                    Triple("회전 전 방향지시등 준비 시간이 짧았어요","확인된 회전 전에 설정된 ${signalLeadMs/1000.0}초 연속 준비 기준을 충족하지 못했어요.","다음 연습에서는 회전 준비를 시작한 시점과 방향지시등을 선택한 시점을 주행 후 비교해보세요.")
                }
                EventKind.OPPOSITE_TURN_SIGNAL in kinds || EventKind.INTERRUPTED_TURN_SIGNAL in kinds -> {
                    priority=ReviewPriority.PRACTICE; strength=EvidenceStrength.SUPPORTED
                    Triple("회전 방향과 방향지시등 기록을 확인해보세요","확인된 회전 방향과 신호 선택이 다르거나, 준비 구간에서 신호가 해제된 기록이 있어요.","주행 후 해당 구간을 복기하며 방향 선택과 해제 시점을 확인해보세요.")
                }
                group.context in setOf("STATIONARY_MANEUVER","LOW_SPEED_MANEUVER","REVERSE_MANEUVER") -> {
                    priority=ReviewPriority.CONTEXT
                    Triple("정차·저속·후진 조작의 조향을 따로 살펴봤어요","이 구간의 조향 변화에는 정차·저속 또는 후진 맥락이 함께 관측됐어요.","일반 주행의 조향 보정과 구분해서 당시 조작 목적을 복기해보세요.")
                }
                group.context == "CURVE" -> {
                    priority=ReviewPriority.CONTEXT; strength=EvidenceStrength.SUPPORTED
                    Triple("커브 구간의 조향 변화예요","도로 형상과 진행 방향이 함께 변한 구간에서 조향 이벤트가 관측됐어요.","주행 후 커브 진입·통과 구간을 나누어 조작이 급하게 바뀐 지점을 살펴보세요.")
                }
                group.context == "CRUISE_CONTROL_ACTIVE" -> {
                    priority=ReviewPriority.CONTEXT
                    Triple("크루즈 동작 맥락을 함께 남겼어요","조작 이벤트와 크루즈 활성 상태가 함께 관측됐어요. 조향 보조 개입 여부는 확인되지 않았어요.","운전자 조작과 보조 기능 동작을 구분할 수 있는 기록을 함께 확인해보세요.")
                }
                group.context == "BRAKING_MANEUVER" -> Triple("제동과 조향이 함께 관측됐어요","브레이크를 사용하는 중 조향 변화가 관측됐어요. 장애물이나 끼어들기 여부는 확인되지 않았어요.","당시 도로와 주변 상황을 함께 복기하며 조작이 겹친 이유를 살펴보세요.")
                EventKind.REPEATED_STEERING_REVERSALS in kinds && group.context == "STRAIGHT" -> {
                    priority=ReviewPriority.PRACTICE; strength=EvidenceStrength.SUPPORTED
                    Triple("직진 구간에서 조향 보정이 반복됐어요","지도와 위치 이동으로 직진 맥락이 확인된 구간에서 좌우 조향 반전이 반복됐어요.","다음 연습 목표로 직진 중 조향 보정의 횟수와 크기를 정하고, 주행 후 이번 기록과 비교해보세요.")
                }
                EventKind.SUSTAINED_SPEED_EXCESS in kinds -> {
                    priority=ReviewPriority.PRACTICE; strength=EvidenceStrength.SUPPORTED
                    Triple("도로 제한속도 대비 속도 기록을 확인해보세요","적용 도로가 확인된 제한속도보다 설정된 복습 기준 이상 높은 속도가 지속됐어요.","주행 후 제한속도 정보가 적용된 구간과 속도 변화를 함께 확인해보세요.")
                }
                EventKind.RAPID_DECELERATION in kinds -> Triple("급격한 감속 구간을 복습해보세요","속도 변화에서 큰 감속이 관측됐어요. 제동이 필요했던 이유는 아직 확인되지 않았어요.","당시 상황과 감속을 시작한 시점을 함께 복기해보세요.")
                EventKind.RAPID_ACCELERATION in kinds -> Triple("가속이 크게 변한 구간이 있어요","연속 속도 관측에서 설정된 가속 복습 기준을 넘은 구간이 있어요.","주행 후 가속을 시작한 상황과 조작이 변한 시점을 함께 살펴보세요.")
                group.context == "INCONSISTENT_INPUTS" -> {
                    priority=ReviewPriority.DATA_CHECK; strength=EvidenceStrength.INSUFFICIENT
                    Triple("서로 맞지 않는 센서 기록이 있어요","기어와 속도 등 입력의 맥락이 일치하지 않아 운전 행동을 확정하지 않았어요.","센서 연결과 시연 입력을 확인한 뒤 다시 기록해보세요.")
                }
                else -> Triple("조향 변화가 있었던 구간을 확인해보세요","조향 변화는 관측됐지만 커브·차로 변경·주변 상황 중 어떤 이유였는지는 확인되지 않았어요.","해당 구간의 도로 상황과 조작 목적을 함께 복기해보세요.")
            }
            LearningFeedback("review-$index-${group.rows.first().startMs}",group.rows.minOf { it.startMs },group.rows.maxOf { it.endMs },priority,
                message.first,message.second,message.third,strength,evidence,missing,kinds)
        }.toMutableList()
        for (curve in practice.maneuvers.filter { it.kind==ManeuverKind.CURVE }) {
            if (feedback.none { it.evidence["context"]=="CURVE" && it.startMs < curve.endMs && it.endMs > curve.startMs })
                feedback += LearningFeedback("curve-${curve.id}",curve.startMs,curve.endMs,ReviewPriority.CONTEXT,
                    "커브를 따라 진행 방향이 바뀌었어요","같은 도로의 형상과 관측된 진행 방향 변화가 일치했어요.",
                    "커브 주행과 직진 중 조향 보정을 구분해서 이번 주행을 복기해보세요.",EvidenceStrength.SUPPORTED,curve.evidence,curve.missing)
        }
        for (maneuver in practice.maneuvers.filter { it.kind in setOf(ManeuverKind.LANE_CHANGE_LEFT_CANDIDATE,ManeuverKind.LANE_CHANGE_RIGHT_CANDIDATE,ManeuverKind.LEFT_TURN_CANDIDATE,ManeuverKind.RIGHT_TURN_CANDIDATE,ManeuverKind.INCOMPLETE) }) {
            val lane = maneuver.kind.name.startsWith("LANE_CHANGE")
            feedback += LearningFeedback("candidate-${maneuver.id}",maneuver.startMs,maneuver.endMs,ReviewPriority.CONTEXT,
                if (lane) "차로 변경으로 보이는 움직임 후보가 있어요" else "아직 확정하지 않은 방향 변화가 있어요",
                if (lane) "진행 방향이 바뀌었다 돌아오고 옆 방향 이동이 관측됐어요. 차선 경계 통과는 확인되지 않았어요." else "진행 방향 변화는 관측됐지만 연결 도로 근거나 조작 전후 관측이 충분하지 않아요.",
                "해당 구간의 도로와 조작 목적을 함께 확인해보세요.",maneuver.strength,maneuver.evidence,maneuver.missing)
        }
        if (turns.adequatelySignalled > 0) feedback += LearningFeedback("prepared-turns",session.startMs,session.endMs,ReviewPriority.CONTEXT,
            "회전 전에 방향지시등을 충분히 준비했어요","평가 가능한 회전 ${turns.adequatelySignalled}건에서 설정된 연속 준비 기준을 충족했어요.",
            "다음 연습에서도 회전 전 준비 과정을 유지해보세요.",EvidenceStrength.SUPPORTED,mapOf("adequatelySignalled" to turns.adequatelySignalled.toString()),emptySet())
        val missing = practice.quality.filter { it.coverage < 0.5 }.map { it.channel }.toSet() +
            assessments.filter { it.category == Category.TURN_SIGNAL_USE }.flatMap { it.reasons }.map { it.name }
        if (missing.isNotEmpty() || session.telemetryTruncated) feedback += LearningFeedback("data-quality",session.startMs,session.endMs,ReviewPriority.DATA_CHECK,
            "관측이 부족한 항목은 평가를 보류했어요","위치·도로·신호 등 근거가 부족한 부분은 운전 실수로 처리하지 않았어요.",
            "다음 기록 전에 필요한 센서와 도로 정보의 연결 상태를 확인해보세요.",EvidenceStrength.INSUFFICIENT,
            practice.quality.associate { it.channel to "${it.eligibleMs}/${it.totalMs}ms" },missing + if (session.telemetryTruncated) setOf("TRUNCATED") else emptySet())
        return feedback.sortedWith(compareBy<LearningFeedback> { it.priority.ordinal }.thenBy { it.startMs }.thenBy { it.id })
    }
}
