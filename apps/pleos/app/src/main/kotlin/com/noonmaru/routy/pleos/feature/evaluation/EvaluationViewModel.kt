package com.noonmaru.routy.pleos.feature.evaluation

import android.app.Application
import com.noonmaru.routy.evaluation.*
import com.noonmaru.routy.pleos.data.telemetry.*
import java.io.File
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.noonmaru.routy.evaluation.DataOrigin
import com.noonmaru.routy.evaluation.DrivingEvaluationEngine
import com.noonmaru.routy.evaluation.DrivingSession
import com.noonmaru.routy.evaluation.EvaluationReport
import com.noonmaru.routy.evaluation.ExampleDrive
import com.noonmaru.routy.evaluation.Observation
import com.noonmaru.routy.evaluation.TimeRange
import com.noonmaru.routy.evaluation.SteeringTimeBasis
import com.noonmaru.routy.pleos.data.telemetry.SpeedSample
import com.noonmaru.routy.pleos.data.telemetry.VehicleSpeedSource
import com.noonmaru.routy.pleos.data.telemetry.VehicleSteeringSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface EvaluationState {
    data object Idle : EvaluationState
    data class Recording(val samples: Int, val status: String) : EvaluationState
    data class Finished(val report: EvaluationReport, val detail: String) : EvaluationState
    data class Unavailable(val message: String) : EvaluationState
}

/** Short, foreground-only acquisition check. Not a background trip recorder. */
class EvaluationViewModel(application: Application) : AndroidViewModel(application) {
    private val source = VehicleSpeedSource(application)
    private val steeringSource = VehicleSteeringSource(application)
    private val catalog = VehicleSensorCatalog(application)
    private val aaos = AaosContextSource(application)
    private val extra = ExtraSdkSource(application)
    private val engine = DrivingEvaluationEngine()
    private val mutableState = MutableStateFlow<EvaluationState>(EvaluationState.Idle)
    val state = mutableState.asStateFlow()
    private var capture: Capture? = null
    private var deadline: Job? = null
    private var generation = 0L

    fun replayExample() {
        cancelCapture()
        val report = engine.evaluate(PracticeScenarios.session("left-late"))
        mutableState.value = EvaluationState.Finished(report, "합성 좌회전 시연 · 위치와 지도에서 회전을 검출하고 늦은 방향지시등 사용을 복습합니다. 실제 차량 데이터가 아닙니다.")
        logReport(report)
    }

    fun permissionDenied() {
        mutableState.value = EvaluationState.Unavailable("차량 속도 권한이 없어 실제 데이터 수집은 시작하지 않았습니다.")
    }

    fun startCapture() {
        cancelCapture()
        val current = Capture(SystemClock.elapsedRealtime(), generation, System.currentTimeMillis())
        capture = current
        mutableState.value = EvaluationState.Recording(0, "30초 동안 공개 SDK 센서를 수집합니다.")
        val onReading: (SensorReading) -> Unit = { value -> viewModelScope.launch {
            if (capture === current && value.atMs >= current.startMs) {
                if (current.telemetry.size < 20_000) current.telemetry += value else current.truncated = true
            }
        } }
        val onAvailability: (SensorAvailability) -> Unit = { value -> viewModelScope.launch {
            if (capture === current) {
                current.availability[value.signal] = value
                val counts = current.availability.values.groupingBy { it.access }.eachCount()
                mutableState.value = EvaluationState.Recording(current.speeds.size,
                    "SDK 채널 ${current.availability.size}개 · 수신 ${current.telemetry.size}개\n$counts")
            }
        } }
        catalog.start(onReading, onAvailability)
        aaos.start(onReading, onAvailability)
        extra.start(onReading, onAvailability)
        source.start(
            onSample = { sample -> viewModelScope.launch { if (capture === current) accept(current, sample) } },
            onStatus = { status -> viewModelScope.launch {
                if (capture === current) {
                    current.status = status
                    mutableState.value = EvaluationState.Recording(current.speeds.size, status)
                }
            } },
            onGap = { viewModelScope.launch {
                if (capture === current && current.gapStart == null) {
                    // Conservative: do not hold the last usable speed across an acquisition failure.
                    current.gapStart = maxOf(current.startMs, current.speeds.lastOrNull()?.atMs ?: current.startMs)
                }
            } },
        )
        steeringSource.start(
            onSample = { sample ->
                if (capture === current && sample.receivedAtMs >= current.startMs && current.angles.size < 2_000) {
                    current.steeringGapStart?.let { start ->
                        if (sample.angleDeg != null && sample.receivedAtMs > start) {
                            current.steeringGaps += TimeRange(start, sample.receivedAtMs)
                            current.steeringGapStart = null
                        }
                    }
                    current.angles += Observation(sample.receivedAtMs, sample.angleDeg, sample.angleDeg != null)
                    Log.d("RoutySteering", "callback angle=${sample.angleDeg} time=${sample.receivedAtMs}")
                    mutableState.value = EvaluationState.Recording(current.speeds.size, "${current.status}\n조향각 ${current.angles.size}개 · ${current.steeringStatus}")
                }
            },
            onStatus = { status -> if (capture === current) { current.steeringStatus = status } },
            onGap = { if (capture === current && current.steeringGapStart == null) {
                current.steeringGapStart = maxOf(current.startMs, current.angles.lastOrNull()?.atMs ?: current.startMs)
            } },
        )
        deadline = viewModelScope.launch { delay(30_000); finish(current) }
    }

    private fun accept(current: Capture, sample: SpeedSample) {
        if (sample.timestampMs < current.startMs) {
            current.olderSamples++
            return // Initial cached readings are not new observations during this capture.
        }
        if (current.speeds.size >= 2_000) {
            current.status = "수집 상한 도달 · 기록을 종료합니다."
            finish(current)
            return
        }
        val available = sample.speedMps != null && sample.sourceTimestampNanos > 0 && sample.timestampMs <= sample.receivedAtMs
        val gapStart = current.gapStart
        if (available && gapStart != null && sample.timestampMs > gapStart) {
            current.gaps += TimeRange(gapStart, sample.timestampMs)
            current.gapStart = null
        }
        current.speeds += Observation(sample.timestampMs, sample.speedMps, available)
        mutableState.value = EvaluationState.Recording(current.speeds.size, current.status)
    }

    private fun finish(current: Capture) {
        if (capture !== current) return
        source.stop()
        steeringSource.stop()
        catalog.stop(); aaos.stop(); extra.stop()
        capture = null
        deadline?.cancel()
        deadline = null
        val end = SystemClock.elapsedRealtime()
        val gaps = current.gaps.mapNotNull { range ->
            val clippedEnd = minOf(range.endMs, end)
            if (clippedEnd > range.startMs) TimeRange(range.startMs, clippedEnd) else null
        }.toMutableList()
        current.gapStart?.let { if (end > it) gaps += TimeRange(it, end) }
        val steeringGaps = current.steeringGaps.toMutableList()
        current.steeringGapStart?.let { if (end > it) steeringGaps += TimeRange(it, end) }
        val session = DrivingSession(current.startMs, end, DataOrigin.LIVE,
            speeds = current.speeds.toList(), speedGaps = gaps,
            steeringAngles = current.angles.toList(), steeringGaps = steeringGaps, steeringTimeBasis = SteeringTimeBasis.CALLBACK_RECEIPT,
            telemetry = current.telemetry.toList(), sensorAvailability = current.availability.values.toList(), telemetryTruncated = current.truncated)
        val intervals = current.speeds.zipWithNext().map { (a, b) -> b.atMs - a.atMs }
        val detail = "실제 AAOS 속도 ${current.speeds.size}개 · 시작 전 캐시 제외 ${current.olderSamples}개" +
            (if (intervals.isEmpty()) " · 수신 간격 확인 불가" else " · 간격 ${intervals.min()}~${intervals.max()}ms") +
            "\n${current.status}\n조향각 ${current.angles.size}개 · ${current.steeringStatus}" +
            "\nSDK 기록 ${current.telemetry.size}개 · ${current.availability.size}개 채널 진단 · 위치·도로·신호의 관측 품질은 결과에서 확인합니다."
        viewModelScope.launch {
            var mapDetail = ""
            val map = withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(getApplication<Application>().filesDir, "road-network.json")
                    if (!file.exists()) null else {
                        require(file.length() <= 4_194_304) { "Map input exceeds 4MiB" }
                        PracticeCodec.liveNetwork(file.readText(),end,System.currentTimeMillis(),current.startEpochMs)
                    }
                }.getOrElse { mapDetail = "Map unavailable: ${it.message}"; null }
            }
            val normalized = PracticeInputs.normalize(session.copy(roadNetwork=map))
            val report = withContext(Dispatchers.Default) { engine.evaluate(normalized) }
            val trace = withContext(Dispatchers.IO) { runCatching { saveTrace(normalized, report) }.getOrElse { "저장 실패: ${it.message}" } }
            // A user may have started a new capture while evaluation was running.
            if (current.generation == generation && capture == null && mutableState.value is EvaluationState.Recording) {
                mutableState.value = EvaluationState.Finished(report, "$detail\n센서 기록: $trace\n$mapDetail")
                logReport(report)
            }
        }
    }

    fun cancelCapture() {
        generation++
        deadline?.cancel()
        deadline = null
        if (capture != null) mutableState.value = EvaluationState.Unavailable("화면을 벗어나 수집을 종료했습니다. 완결되지 않은 수집은 평가하지 않습니다.")
        capture = null
        source.stop()
        steeringSource.stop()
        catalog.stop(); aaos.stop(); extra.stop()
    }

    override fun onCleared() { cancelCapture(); super.onCleared() }

    private fun logReport(report: EvaluationReport) {
        Log.i("RoutyEvaluation", "origin=${report.origin} engine=${report.engineVersion} rules=${report.config.ruleVersion} events=${report.events.map { it.kind }}")
        report.assessments.forEach { Log.i("RoutyEvaluation", "${it.category}: ${it.status} eligibleMs=${it.eligibleMs} reasons=${it.reasons}") }
        Log.i("RoutyEvaluation", "steering=${report.steering}")
        Log.i("RoutyEvaluation", "telemetry=${report.telemetry.readings} channels=${report.telemetry.channels} availability=${report.telemetry.availability.groupingBy { it.access }.eachCount()}")
        report.contextFindings.forEach { Log.i("RoutyEvaluation", "fusion=$it") }
        Log.i("RoutyEvaluation", "maneuvers=${report.practice.maneuvers.map { it.kind }} quality=${report.practice.quality.map { it.channel to it.eligibleMs }}")
        Log.i("RoutyEvaluation", "feedback=${report.feedback.map { it.priority to it.title }}")
    }

    private fun saveTrace(session: DrivingSession, report: EvaluationReport): String {
        val root = File(getApplication<Application>().filesDir, "sensor-traces").apply { mkdirs() }
        val output = File(root, "latest.json")
        output.writeText(JsonDocuments.write(TraceReplay.recording(session,report)))
        return "files/sensor-traces/latest.json"
    }

    private class Capture(val startMs: Long, val generation: Long, val startEpochMs: Long) {
        val telemetry = mutableListOf<SensorReading>()
        val availability = linkedMapOf<String, SensorAvailability>()
        var truncated = false
        val speeds = mutableListOf<Observation<Double>>()
        val gaps = mutableListOf<TimeRange>()
        var gapStart: Long? = null
        var olderSamples = 0
        var status = "속도 관측 대기 중"
        val angles = mutableListOf<Observation<Double>>()
        val steeringGaps = mutableListOf<TimeRange>()
        var steeringGapStart: Long? = null
        var steeringStatus = "조향각 관측 대기 중"
    }
}
