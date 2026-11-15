package org.eowoo.routy.pleos.feature.evaluation

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import org.eowoo.routy.evaluation.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.eowoo.routy.evaluation.AssessmentStatus
import org.eowoo.routy.evaluation.Category
import org.eowoo.routy.evaluation.DataOrigin
import org.eowoo.routy.evaluation.EvaluationReport
import org.eowoo.routy.evaluation.EventKind
import java.util.Locale

@Composable
fun EvaluationPanel(model: EvaluationViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val autoEnabled by org.eowoo.routy.pleos.feature.trip.AutoRecording.enabled.collectAsStateWithLifecycle()
    val scenarioStatus by model.scenarioStatus.collectAsStateWithLifecycle()
    val uploadStatus by model.uploadStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it["android.car.permission.CAR_SPEED"] == true || context.checkSelfPermission("android.car.permission.CAR_SPEED") == PackageManager.PERMISSION_GRANTED) model.startCapture() else model.permissionDenied()
    }
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) model.cancelCapture() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); model.cancelCapture() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("주행 평가", style = MaterialTheme.typography.headlineMedium)
        Text("실험용 학습 지표입니다. 급격한 조작을 운전 실수로 확정하거나 종합 점수로 환산하지 않습니다.")
        Text(uploadStatus)
        if(autoEnabled)Text("자동 기록을 끈 뒤 30초 진단 수집을 시작해주세요.")
        OutlinedButton(onClick=model::retryUploads,modifier=Modifier.heightIn(min=64.dp)) { Text("대기·확인 필요 기록 다시 전송") }
        Button(onClick = model::replayExample, enabled = state !is EvaluationState.Recording,
            modifier = Modifier.heightIn(min = 64.dp)) { Text("회전 감지·학습 피드백 합성 시연") }
        OutlinedButton(onClick = {
            val speedPermission = "android.car.permission.CAR_SPEED"
            val requested = mutableListOf(speedPermission)
            val location = "pleos.car.permission.FUSED_LOCATION"
            if (org.eowoo.routy.pleos.BuildConfig.DEBUG) requested += location
            val missing = requested.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty()) model.startCapture() else permission.launch(missing.toTypedArray())
        }, enabled = state !is EvaluationState.Recording && !autoEnabled, modifier = Modifier.heightIn(min = 64.dp)) {
            Text("실제 센서 전체 30초 수집·분석")
        }
        if (org.eowoo.routy.pleos.BuildConfig.DEBUG) {
            OutlinedButton(onClick=if(scenarioStatus.isEmpty()) model::watchScenarios else model::cancelCapture,
                enabled=state !is EvaluationState.Recording,modifier=Modifier.heightIn(min=64.dp)) {
                Text(if(scenarioStatus.isEmpty()) "시나리오 완료 자동 분석 연결" else "자동 분석 연결 해제")
            }
            if(scenarioStatus.isNotEmpty())Text(scenarioStatus)
            OutlinedButton(onClick=model::replaySavedScenario,enabled=state !is EvaluationState.Recording,
                modifier=Modifier.heightIn(min=64.dp)) { Text("저장된 시나리오 복습") }
            OutlinedButton(onClick=model::analyzeEnvironment, enabled=state !is EvaluationState.Recording,
                modifier=Modifier.heightIn(min=64.dp)) { Text("완료된 환경 기록 분석") }
        }
        when (val current = state) {
            EvaluationState.Idle -> Text("합성 재생과 실제 센서 수집은 별도로 표시됩니다. 실제 수집은 포그라운드 서비스에서 계속됩니다.")
            is EvaluationState.Recording -> Text("실제 센서 수집 중 · 속도 ${current.samples}개\n${current.status}")
            is EvaluationState.Unavailable -> Text(current.message)
            is EvaluationState.Finished -> {
                Text(current.detail)
                ReportCard(current.report)
            }
        }
    }
}

@Composable
private fun ReportCard(report: EvaluationReport) {
    var diagnosticsExpanded by rememberSaveable(report.startMs,report.origin) { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(when (report.origin) {
                DataOrigin.SYNTHETIC -> "합성 데이터 결과 · 실제 주행 평가 아님"
                DataOrigin.LIVE -> "실제 AAOS 입력 결과"
                DataOrigin.RECORDED -> "저장된 입력 재생 결과"
            }, style = MaterialTheme.typography.titleLarge)
            Text("규칙: ${report.config.ruleVersion} · 차량/도로 정확도 검증 전")
            report.environment?.let { Text("환경 평가는 가상 환경 기반입니다 · ${it.frames} 프레임 · 제외 ${it.excluded}개") }
            EnvironmentOverview(report)
            PracticeOverview(report)
            OutlinedButton(onClick={ diagnosticsExpanded = !diagnosticsExpanded },
                modifier=Modifier.heightIn(min=64.dp).semantics { stateDescription=if (diagnosticsExpanded) "펼침" else "접힘" }) {
                Text(if (diagnosticsExpanded) "상세 센서 진단 접기" else "상세 센서 진단 보기")
            }
            if (diagnosticsExpanded) {
                report.assessments.forEach {
                    val status = when (it.status) {
                        AssessmentStatus.ASSESSED -> "지표 산출"
                        AssessmentStatus.PARTIAL -> "일부 구간 산출"
                        AssessmentStatus.NOT_ASSESSED -> "미평가"
                        AssessmentStatus.NO_OPPORTUNITY -> "평가 대상 상황 없음"
                    }
                    val coverage = if (it.category == Category.TURN_SIGNAL_USE) "회전 입력 ${it.opportunities}건 / 평가 ${it.evaluatedOpportunities}건"
                        else "관측 ${seconds(it.eligibleMs)}초 / 제외 ${seconds(it.excludedMs)}초"
                    Text("${it.category.label()}: $status · $coverage")
                    if (it.reasons.isNotEmpty()) Text("제외 근거: ${it.reasons.joinToString { reason -> reason.name }}", style = MaterialTheme.typography.bodySmall)
                }
                Text("SDK 기록 ${report.telemetry.readings}개 · 실제 콜백 ${report.telemetry.updates}개 · 채널 ${report.telemetry.channels}개")
                Text("지원 진단: ${report.telemetry.availability.groupingBy { it.access }.eachCount()}")
                if (report.telemetry.truncated) Text("수집 상한 도달: 맥락 융합은 보류합니다.")
                report.contextFindings.forEach {
                    Text("복합 맥락: ${it.context} · 근거 ${it.evidence.filterKeys { key -> key != "interpretation" }} · 미확인 ${it.missing}")
                }
                Text("복습 이벤트 ${report.events.size}건", style = MaterialTheme.typography.titleMedium)
                report.steering.meanAbsoluteRateDegPerSec?.let {
                    Text("조향 평균/최대 변화 속도: ${String.format(Locale.ROOT, "%.1f / %.1f", it, report.steering.peakAbsoluteRateDegPerSec)}°/s · 유의미한 방향 반전 ${report.steering.significantReversals}회")
                }
                Text("조향 시각 기준: ${report.steering.timeBasis} · 차로 위치/유지 판정은 아닙니다.")
                report.events.forEach { Text("${it.kind.label()} · ${seconds(it.startMs - report.startMs)}~${seconds(it.endMs - report.startMs)}초") }
                Text("저크·미세한 부드러움 점수는 보정과 검증 전까지 산출하지 않습니다.")
            }
        }
    }
}

@Composable
private fun PracticeOverview(report: EvaluationReport) {
    Text("이번 연습의 복습",style=MaterialTheme.typography.titleLarge)
    report.feedback.filterNot { report.environment?.review!=null && it.id.startsWith("environment-") }.forEach { feedback ->
        var expanded by rememberSaveable(report.startMs,feedback.id) { mutableStateOf(false) }
        Card(modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(when (feedback.priority) {
                    ReviewPriority.PRACTICE -> "다음 연습 목표"
                    ReviewPriority.REVIEW -> "상황 복습"
                    ReviewPriority.CONTEXT -> "상황 참고"
                    ReviewPriority.DATA_CHECK -> "데이터 확인"
                },style=MaterialTheme.typography.labelLarge)
                Text(feedback.title,style=MaterialTheme.typography.titleMedium)
                Text(feedback.observation,style=MaterialTheme.typography.bodyLarge)
                Text(feedback.nextPractice,style=MaterialTheme.typography.bodyLarge)
                Text("${seconds(feedback.startMs-report.startMs)}~${seconds(feedback.endMs-report.startMs)}초 · "+when(feedback.strength) {
                    EvidenceStrength.SUPPORTED -> "관측 근거 확인"
                    EvidenceStrength.LIMITED -> "일부 근거로 설명"
                    EvidenceStrength.INSUFFICIENT -> "추가 근거 필요"
                })
                OutlinedButton(onClick={ expanded = !expanded },modifier=Modifier.heightIn(min=64.dp).semantics {
                    stateDescription=if (expanded) "펼침" else "접힘"
                }) { Text(if (expanded) "이 피드백의 판단 근거 접기" else "이 피드백의 판단 근거 보기") }
                if (expanded) {
                    Text(feedback.evidence.entries.joinToString("\n") { "${it.key}: ${it.value}" })
                    if (feedback.missing.isNotEmpty()) Text("미확인 근거: ${feedback.missing.joinToString()}")
                }
            }
        }
    }
    Text("행동 관측 ${report.practice.maneuvers.size}건",style=MaterialTheme.typography.titleMedium)
    report.practice.maneuvers.forEach {
        Text("${it.kind.label()} · ${seconds(it.startMs-report.startMs)}~${seconds(it.endMs-report.startMs)}초")
    }
    Text("입력 품질 · 계산에 사용 가능한 구간",style=MaterialTheme.typography.titleMedium)
    report.practice.quality.forEach {
        val label=when(it.channel) {
            "trajectory" -> "위치·헤딩 이동"
            "roadMatching" -> "도로 매칭"
            "accelerationInput" -> "가감속 입력"
            "steeringInput" -> "조향 입력"
            else -> "방향지시등 선택"
        }
        Text("$label: ${seconds(it.eligibleMs)} / ${seconds(it.totalMs)}초 (${String.format(Locale.ROOT,"%.0f",it.coverage*100)}%)")
    }
    Text("관측 비율은 판단 정확도나 운전 점수가 아닙니다. 실제 도로의 차선 경계는 별도 관측이 필요합니다.")
}

@Composable
private fun EnvironmentOverview(report:EvaluationReport) {
    val result=report.environment ?: return
    val review=result.review ?: return
    Text("환경 관측 범위",style=MaterialTheme.typography.titleLarge)
    review.quality.forEach { q ->
        val title=when(q.channel) { "LANE_KEEPING" -> "차로 유지"; "FOLLOWING_DISTANCE" -> "차간거리"; "LANE_CHANGE_SIGNAL" -> "차로 변경 방향지시등"; else -> "신호 대응" }
        Text("$title · 평가 가능 ${seconds(q.eligibleMs)}초 / 대상 상황 없음 ${seconds(q.noOpportunityMs)}초 / 제외 ${seconds(q.excludedMs)}초")
        if(q.reasons.isNotEmpty())Text(q.reasons.entries.joinToString(" · ") { (key,ms)->"${EnvironmentReason.valueOf(key).label} ${seconds(ms)}초" },style=MaterialTheme.typography.bodyMedium)
    }
    Text("관측 수치",style=MaterialTheme.typography.titleMedium)
    if(review.metrics.isEmpty())Text("유효한 수치 근거가 없습니다")
    review.metrics.forEach { (key,value)->Text(metricLabel(key,value)) }
    Text("시간순 복습 · ${review.groups.size}개 상황",style=MaterialTheme.typography.titleLarge)
    if(review.groups.isEmpty())Text("관측된 복습 이벤트가 없습니다. 제외 시간과 평가 대상 상황도 함께 확인하세요.")
    review.groups.forEach { group ->
        var expanded by rememberSaveable(report.startMs,group.id) { mutableStateOf(false) }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("${seconds(group.startMs-report.startMs)}~${seconds(group.endMs-report.startMs)}초 · "+if(group.limited) "판단 보류·추가 관측 필요" else "합성 환경 관측",style=MaterialTheme.typography.labelLarge)
                Text(group.title,style=MaterialTheme.typography.titleMedium)
                Text(group.nextPractice)
                group.evidence.filterKeys { it!="durationMs" }.forEach { (key,value)->Text(metricLabel(key,value)) }
                OutlinedButton(onClick={ expanded = !expanded },modifier=Modifier.heightIn(min=64.dp).semantics { stateDescription=if(expanded) "펼침" else "접힘" }) {
                    Text(if(expanded) "원본 이벤트 접기" else "원본 이벤트와 판단 근거 보기")
                }
                if(expanded)result.events.filter { it.kind in group.kinds && it.startMs<=group.endMs && it.endMs>=group.startMs }.forEach {
                    Text("${it.feedback}\n${seconds(it.startMs-report.startMs)}~${seconds(it.endMs-report.startMs)}초 · ${it.detail}")
                }
            }
        }
    }
    Text("THW는 현재 속도로 앞차 위치에 도달하는 시간, TTC는 현재 접근 속도가 유지될 때 간격이 소진되는 시간입니다. 합성 환경의 학습 참고값이며 실제 안전을 보장하지 않습니다.")
}

private fun metricLabel(key:String,value:Double):String {
    val label=when(key) { "minLaneClearanceM" -> "차로 유지 구간 최소 차체 여유"; "minGapM" -> "앞차 최소 차체 간격"; "minHeadwayS" -> "최소 시간 간격 THW"; "minTtcS" -> "최소 접근 시간 TTC"; "redStopLineGapM" -> "적색 정차 시 최소 정지선 여유"; "signalPreparationMs" -> "방향지시등 사전 연속 관측"; "signalOffDurationMs" -> "변경 중 관측된 방향지시등 해제"; else -> "관측 지속 시간" }
    val unit=if(key.endsWith("M")) "m" else "초"
    return "$label: ${String.format(Locale.ROOT,"%.2f",if(key.endsWith("Ms")) value/1000 else value)}$unit"
}

private fun ManeuverKind.label() = when(this) {
    ManeuverKind.STRAIGHT -> "직진 맥락"
    ManeuverKind.CURVE -> "도로 형상을 따른 커브"
    ManeuverKind.LEFT_TURN -> "연결 도로와 헤딩으로 확인한 좌회전"
    ManeuverKind.RIGHT_TURN -> "연결 도로와 헤딩으로 확인한 우회전"
    ManeuverKind.LEFT_TURN_CANDIDATE -> "좌회전 후보 · 도로 근거 미확인"
    ManeuverKind.RIGHT_TURN_CANDIDATE -> "우회전 후보 · 도로 근거 미확인"
    ManeuverKind.LANE_CHANGE_LEFT_CANDIDATE -> "좌측 차로 변경 후보 · 경계 통과 미확인"
    ManeuverKind.LANE_CHANGE_RIGHT_CANDIDATE -> "우측 차로 변경 후보 · 경계 통과 미확인"
    ManeuverKind.LOW_SPEED_MANEUVER -> "저속 조작"
    ManeuverKind.REVERSE_MANEUVER -> "후진 맥락"
    ManeuverKind.INCOMPLETE -> "조작 전후 관측 미완료"
    ManeuverKind.UNKNOWN -> "행동 미확인"
}
private fun seconds(ms: Long) = String.format(Locale.ROOT, "%.1f", ms / 1_000.0)
private fun Category.label() = when (this) {
    Category.SPEED_MANAGEMENT -> "제한속도 대비 속도 관리"
    Category.ACCELERATION_CONTROL -> "가감속 패턴"
    Category.TURN_SIGNAL_USE -> "회전 시 방향지시등"
    Category.STEERING_CONTROL -> "조향 조작 패턴"
    Category.LANE_KEEPING -> "차로 유지"
    Category.TRAFFIC_SIGNAL_RESPONSE -> "신호 대응"
    Category.FOLLOWING_DISTANCE -> "차간거리"
}
private fun EventKind.label() = when (this) {
    EventKind.SUSTAINED_SPEED_EXCESS -> "설정 기준보다 높은 속도 지속"
    EventKind.RAPID_ACCELERATION -> "급격한 가속 관측"
    EventKind.RAPID_DECELERATION -> "급격한 감속 관측 · 원인 미확인"
    EventKind.MISSING_TURN_SIGNAL -> "회전 전 방향지시등 미사용 관측"
    EventKind.LATE_TURN_SIGNAL -> "회전 전 방향지시등 준비 시간 부족"
    EventKind.OPPOSITE_TURN_SIGNAL -> "회전 방향과 다른 방향지시등"
    EventKind.INTERRUPTED_TURN_SIGNAL -> "회전 전 방향지시등 해제 관측"
    EventKind.RAPID_STEERING -> "급격한 핸들 회전 관측 · 원인 미확인"
    EventKind.REPEATED_STEERING_REVERSALS -> "반복적인 좌우 조향 조작 관측"
}
