package com.noonmaru.routy.pleos.feature.evaluation

import android.app.Application
import com.noonmaru.routy.evaluation.*
import com.noonmaru.routy.pleos.data.telemetry.*
import com.noonmaru.routy.pleos.data.records.RecordSync
import com.noonmaru.routy.pleos.data.records.ArchiveRecovery
import androidx.work.WorkManager
import java.io.File
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive

sealed interface EvaluationState {
    data object Idle : EvaluationState
    data class Recording(val samples: Int, val status: String) : EvaluationState
    data class Finished(val report: EvaluationReport, val detail: String) : EvaluationState
    data class Unavailable(val message: String) : EvaluationState
}

/** Acquisition owner is supplied by the service, independent of Activity lifecycle. */
class EvaluationController(private val application: Application, private val scope: CoroutineScope) {
    private val source = VehicleSpeedSource(application)
    private val steeringSource = VehicleSteeringSource(application)
    private val catalog = VehicleSensorCatalog(application)
    private val aaos = AaosContextSource(application)
    private val extra = ExtraSdkSource(application)
    private val engine = DrivingEvaluationEngine()
    private val mutableState = MutableStateFlow<EvaluationState>(EvaluationState.Idle)
    val state = mutableState.asStateFlow()
    @Volatile private var capture: Capture? = null
    private var deadline: Job? = null
    private var generation = 0L
    private var scenarioJob:Job?=null
    private val mutableScenarioStatus=MutableStateFlow("")
    val scenarioStatus=mutableScenarioStatus.asStateFlow()
    private var lastScenarioRun:String?=null
    private val mutableUploadStatus=MutableStateFlow("서버 전송 상태 확인 중")
    val uploadStatus=mutableUploadStatus.asStateFlow()
    init {
        RecordSync.schedule(application)
        scope.launch {
            WorkManager.getInstance(application).getWorkInfosForUniqueWorkFlow("driving-record-uploads").collect {
                mutableUploadStatus.value=withContext(Dispatchers.IO) { runCatching { RecordSync.status(application) }.getOrElse { "전송 기록 확인 실패: ${it.message}" } }
            }
        }
    }
    fun retryUploads() {
        scope.launch(Dispatchers.IO) {
            runCatching { RecordSync.outbox(application).retryFailed();RecordSync.schedule(application,force=true) }
                .onFailure { mutableUploadStatus.value="전송 재시도 실패: ${it.message}" }
        }
    }
    private fun enqueueUpload(id:String,report:EvaluationReport,startedEpochMs:Long,scenarioId:String?=null):String {
        return runCatching {
            RecordSync.outbox(application).enqueue(id,report,startedEpochMs,scenarioId)
            RecordSync.schedule(application)
            "서버 전송 대기 목록에 저장했습니다"
        }.getOrElse { throw IllegalStateException("서버 전송 준비 실패: ${it.message}",it) }
    }


    fun watchScenarios() {
        if(!com.noonmaru.routy.pleos.BuildConfig.DEBUG)return
        cancelCapture()
        lastScenarioRun=null
        val requestedGeneration=generation
        mutableScenarioStatus.value="콘솔 연결 중"
        scenarioJob=scope.launch {
            val client=ScenarioSource()
            while(isActive && generation==requestedGeneration) {
                try {
                    val snapshot=withContext(Dispatchers.IO) { client.state() }
                    mutableScenarioStatus.value=when(snapshot.status) {
                        "running" -> "시나리오 실행 중 · 완료되면 자동 분석합니다"
                        "completed" -> "시나리오 완료 · 다음 실행도 자동 분석합니다"
                        "stopped" -> "시나리오 일시 정지 · 완료 전에는 평가하지 않습니다"
                        else -> "콘솔 연결됨 · 시나리오 시작을 기다립니다"
                    }
                    if(snapshot.status=="completed" && snapshot.runId!=lastScenarioRun) {
                        val frames=withContext(Dispatchers.IO) { client.completed(snapshot) }
                        val report=withContext(Dispatchers.Default) { engine.evaluate(ScenarioReview.session(frames)) }
                        val document=ScenarioReview.recording(frames,report)
                        // Validate the stored representation too, before publishing a review.
                        withContext(Dispatchers.Default) {
                            val restored=JsonDocuments.read(JsonDocuments.write(document))
                            TraceReplay.verifyCaptured(restored,engine.evaluate(TraceReplay.session(restored)))
                        }
                        if(generation==requestedGeneration) {
                            val sync=withContext(Dispatchers.IO) {
                                saveScenario(document)
                                enqueueUpload(snapshot.runId,report,System.currentTimeMillis()-(report.endMs-report.startMs),snapshot.scenarioId)
                            }
                            lastScenarioRun=snapshot.runId
                            mutableState.value=EvaluationState.Finished(report,"${snapshot.scenarioId} 완료 · 합성 환경 기록 자동 분석\n저장: files/scenario-reviews/latest.json\n$sync")
                            logReport(report)
                        }
                    }
                } catch(e:CancellationException) { throw e
                } catch(e:Exception) { mutableScenarioStatus.value="콘솔 연결·분석 대기: ${e.message} · 자동 재시도합니다" }
                delay(1000)
            }
        }
    }

    private fun saveScenario(document:JsonObject) {
        val root=File(application.filesDir,"scenario-reviews").apply { mkdirs() }
        android.util.AtomicFile(File(root,"latest.json")).let { file ->
            val output=file.startWrite()
            try { output.write(JsonDocuments.write(document).toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
            catch(e:Exception) { file.failWrite(output); throw e }
        }
    }

    fun replaySavedScenario() {
        cancelCapture()
        val requestedGeneration=generation
        scope.launch {
            try {
                val report=withContext(Dispatchers.IO) {
                    val file=File(application.filesDir,"scenario-reviews/latest.json")
                    val text=android.util.AtomicFile(file).openRead().use { input ->
                        require(file.length() in 1..4_194_304) { "저장된 시나리오 기록이 없거나 용량을 초과했습니다" }
                        input.bufferedReader().use { it.readText() }
                    }
                    val document=JsonDocuments.read(text)
                    engine.evaluate(TraceReplay.session(document)).also { TraceReplay.verifyCaptured(document,it) }
                }
                if(generation==requestedGeneration)mutableState.value=EvaluationState.Finished(report,"저장된 합성 시나리오 재생 · 원래 분석 결과 일치 확인")
            } catch(e:CancellationException) { throw e
            } catch(e:Exception) { if(generation==requestedGeneration)mutableState.value=EvaluationState.Unavailable("기록 재생 실패: ${e.message}") }
        }
    }

    fun analyzeEnvironment() {
        if (!com.noonmaru.routy.pleos.BuildConfig.DEBUG) return
        cancelCapture()
        val requestedGeneration=generation
        scope.launch {
            try {
                val result=withContext(Dispatchers.IO) {
                    val client=ScenarioSource()
                    val frames=client.completed(client.state())
                    engine.evaluate(ScenarioReview.session(frames))
                }
                if(generation==requestedGeneration) mutableState.value=EvaluationState.Finished(result,"가상 환경 수동 분석 · 합성 데이터")
            } catch(e:CancellationException) { throw e
            } catch(e: Exception) {
                if(generation==requestedGeneration) mutableState.value=EvaluationState.Unavailable("가상 환경 수집 실패: ${e.message}")
            }
        }
    }

    fun replayExample() {
        cancelCapture()
        val report = engine.evaluate(PracticeScenarios.session("left-late"))
        mutableState.value = EvaluationState.Finished(report, "합성 좌회전 시연 · 위치와 지도에서 회전을 검출하고 늦은 방향지시등 사용을 복습합니다. 실제 차량 데이터가 아닙니다.")
        logReport(report)
    }

    fun permissionDenied() {
        mutableState.value = EvaluationState.Unavailable("차량 속도 권한이 없어 실제 데이터 수집은 시작하지 않았습니다.")
    }

    /** Null deadline: D starts a trip, continuous P (or disabling the setting) ends it. */
    fun startCapture(durationMs:Long?=30_000) {
        require(durationMs==null || durationMs>=5_000)
        check(capture==null && mutableState.value !is EvaluationState.Recording)
        cancelCapture()
        val current=Capture(SystemClock.elapsedRealtime(),generation,System.currentTimeMillis())
        check(ArchiveRecovery.claim(current.sessionId))
        capture=current
        current.writer=scope.launch(Dispatchers.IO) {
            var archive:TripArchive?=null
            try {
                archive=TripArchive(File(application.filesDir,"sensor-traces/${current.sessionId}"),current.startMs,current.startEpochMs)
                current.archive=archive
                for(row in current.sink.rows){
                    val document=JsonDocuments.read(row.bytes.toString(Charsets.UTF_8))
                    @Suppress("UNCHECKED_CAST")
                    archive.append(row.atMs,document["kind"] as String,document["value"] as JsonObject)
                }
                current.sink.failure?.let{throw it}
                current.finalEnd?.let { archive.finish(it) }
            } catch(e:CancellationException){throw e
            } catch(e:Exception) {
                current.failure=e
                scope.launch(Dispatchers.Main) {
                    if(capture===current){cancelCapture();mutableState.value=EvaluationState.Unavailable("센서 원본 저장 실패: ${e.message}")}
                }
            } finally {archive?.close();if(current.finalEnd==null)ArchiveRecovery.release(current.sessionId)}
        }
        current.tickJob=scope.launch {
            while(capture===current){delay(1000);if(capture===current)record(current,"tick",emptyMap())}
        }
        if(com.noonmaru.routy.pleos.BuildConfig.DEBUG)current.environmentJob=scope.launch(Dispatchers.IO) {
            val environmentSource=EnvironmentSource()
            while(capture===current) {
                val row=withContext(Dispatchers.IO){runCatching{environmentSource.poll()}.getOrNull()}
                if(capture===current && row!=null && row.sessionAtMs>=current.startMs)
                    record(current,"environment",EnvironmentIntegration.encode(listOf(row)).single())
                delay(100)
            }
        }
        mutableState.value=EvaluationState.Recording(0,"차량 센서 원본을 디스크에 기록합니다. 화면을 벗어나도 계속됩니다.")
        val onReading:(SensorReading)->Unit={ row ->
            if(capture===current && row.atMs>=current.startMs) {
                record(current,"readings",mapOf("signal" to row.signal,"atMs" to row.atMs,"kind" to row.kind.name,
                    "number" to row.number,"flag" to row.flag,"state" to row.state,"fields" to row.fields,
                    "available" to row.available,"providerTimestampMs" to row.providerTimestampMs))
                current.telemetryCount.incrementAndGet()
            }
        }
        val onAvailability:(SensorAvailability)->Unit={ value ->scope.launch {
            if(capture===current){
                if(value.signal !in current.availability && current.availability.size>=512){failCapture(current,"센서 채널 정보를 저장하지 못했어요.")}
                else current.availability[value.signal]=value
            }
        } }
        catalog.start(onReading,onAvailability);aaos.start(onReading,onAvailability);extra.start(onReading,onAvailability)
        source.start(onSample={sample->scope.launch{if(capture===current)accept(current,sample)}},
            onStatus={status->scope.launch{if(capture===current){current.status=status;mutableState.value=EvaluationState.Recording(current.speedCount,status)}}},
            onGap={scope.launch{if(capture===current && current.gapStart==null){
                current.gapStart=maxOf(current.startMs,current.lastSpeedAt?:current.startMs)
                record(current,"speedGapStart",mapOf("atMs" to current.gapStart))
            }}})
        steeringSource.start(onSample={ sample ->
            if(capture===current && sample.receivedAtMs>=current.startMs) {
                if(current.steeringGapStart!=null && sample.angleDeg!=null){
                    record(current,"steeringGapEnd",mapOf("atMs" to sample.receivedAtMs));current.steeringGapStart=null
                }
                record(current,"steering",mapOf("atMs" to sample.receivedAtMs,"value" to sample.angleDeg,"available" to (sample.angleDeg!=null)))
                current.angleCount++;current.lastSteeringAt=sample.receivedAtMs
                mutableState.value=EvaluationState.Recording(current.speedCount,"${current.status}\n조향각 ${current.angleCount}개 · ${current.steeringStatus}")
            }
        },onStatus={if(capture===current)current.steeringStatus=it},onGap={
            if(capture===current && current.steeringGapStart==null){
                current.steeringGapStart=maxOf(current.startMs,current.lastSteeringAt?:current.startMs)
                record(current,"steeringGapStart",mapOf("atMs" to current.steeringGapStart))
            }
        })
        deadline=durationMs?.let{duration->scope.launch{delay(duration);finish(current)}}
    }
    private fun record(current:Capture,kind:String,value:JsonObject) {
        if(capture!==current)return // Late callback after acquisition stopped is outside this trip.
        if(!current.sink.offer(SystemClock.elapsedRealtime(),kind,value))scope.launch(Dispatchers.Main){
            if(capture===current)failCapture(current,"저장 장치가 센서 수신을 따라가지 못했어요. 원본 파일을 확인해주세요.")
        }
    }
    private fun failCapture(current:Capture,message:String) {
        if(capture!==current)return
        cancelCapture();mutableState.value=EvaluationState.Unavailable(message)
    }
    private fun accept(current:Capture,sample:SpeedSample) {
        if(sample.timestampMs<current.startMs){current.olderSamples++;return}
        val available=sample.speedMps!=null && sample.sourceTimestampNanos>0 && sample.timestampMs<=sample.receivedAtMs
        if(available && current.gapStart!=null && sample.timestampMs>current.gapStart!!){
            record(current,"speedGapEnd",mapOf("atMs" to sample.timestampMs));current.gapStart=null
        }
        record(current,"speed",mapOf("atMs" to sample.timestampMs,"value" to sample.speedMps,"available" to available))
        current.speedCount++;current.lastSpeedAt=sample.timestampMs
        mutableState.value=EvaluationState.Recording(current.speedCount,current.status)
    }
    private fun finish(current:Capture) {
        if(capture!==current)return
        current.environmentJob?.cancel();current.tickJob?.cancel()
        source.stop();steeringSource.stop();catalog.stop();aaos.stop();extra.stop()
        capture=null;deadline?.cancel();deadline=null
        scope.launch {
            try {
                delay(1) // End follows even a callback delivered in the stop clock tick.
                current.finalEnd=SystemClock.elapsedRealtime();current.sink.close()
                val result=withContext(Dispatchers.IO) {
                    current.writer?.join()
                    (current.failure?:current.sink.failure)?.let{throw it}
                    val archive=checkNotNull(current.archive)
                    val file=File(application.filesDir,"road-network.json")
                    val network=if(!file.exists())null else runCatching {
                        require(file.length()<=4_194_304)
                        PracticeCodec.liveNetwork(file.readText(),current.finalEnd!!,System.currentTimeMillis(),current.startEpochMs)
                    }.getOrNull()
                    val evaluated=archive.evaluate(current.availability.values.toList(),network)
                    RecordSync.outbox(application).enqueueArchive(current.sessionId,evaluated,current.startEpochMs)
                    RecordSync.schedule(application)
                    val index=mapOf("schemaVersion" to 2,"storage" to "SEGMENTED","sessionId" to current.sessionId,
                        "origin" to "LIVE","startMs" to current.startMs,"endMs" to current.finalEnd,"segments" to archive.segments,
                        "truncated" to false,"analysis" to evaluated.summary,"rawDirectory" to current.sessionId)
                    val root=File(application.filesDir,"sensor-traces")
                    TripArchive.atomic(File(root,"${current.sessionId}.json"),index)
                    TripArchive.atomic(File(root,"latest.json"),index)
                    TripArchive.atomic(File(archive.directory,"complete.json"),index)
                    evaluated
                }
                if(current.generation==generation && capture==null && mutableState.value is EvaluationState.Recording){
                    mutableState.value=EvaluationState.Finished(result.report,"속도 ${current.speedCount}개 · 조향 ${current.angleCount}개 · SDK ${current.telemetryCount.get()}개\n원본 디스크 저장 · 상세 평가 ${result.parts}개 조각 전송 대기")
                    logReport(result.report)
                }
            }catch(e:CancellationException){throw e
            }catch(e:Exception){mutableState.value=EvaluationState.Unavailable("평가 기록 저장 실패: ${e.message}")}
            finally {ArchiveRecovery.release(current.sessionId)}
        }
    }
    val currentSessionId:String? get()=capture?.sessionId
    fun finishCapture(){capture?.let(::finish)}
    fun cancelCapture() {
        generation++;scenarioJob?.cancel();scenarioJob=null;mutableScenarioStatus.value=""
        capture?.let {current->
            current.environmentJob?.cancel();current.tickJob?.cancel();current.sink.close();current.writer?.cancel()
            mutableState.value=EvaluationState.Unavailable("수집이 중단됐어요. 완료되지 않은 디스크 기록은 평가 완료로 표시하지 않습니다.")
        }
        capture=null;deadline?.cancel();deadline=null
        source.stop();steeringSource.stop();catalog.stop();aaos.stop();extra.stop()
    }
    fun close(){cancelCapture()}

    private fun logReport(report: EvaluationReport) {
        Log.i("RoutyEvaluation", "origin=${report.origin} engine=${report.engineVersion} rules=${report.config.ruleVersion} events=${report.events.map { it.kind }}")
        report.assessments.forEach { Log.i("RoutyEvaluation", "${it.category}: ${it.status} eligibleMs=${it.eligibleMs} reasons=${it.reasons}") }
        Log.i("RoutyEvaluation", "steering=${report.steering}")
        Log.i("RoutyEvaluation", "telemetry=${report.telemetry.readings} channels=${report.telemetry.channels} availability=${report.telemetry.availability.groupingBy { it.access }.eachCount()}")
        report.contextFindings.forEach { Log.i("RoutyEvaluation", "fusion=$it") }
        Log.i("RoutyEvaluation", "maneuvers=${report.practice.maneuvers.map { it.kind }} quality=${report.practice.quality.map { it.channel to it.eligibleMs }}")
        Log.i("RoutyEvaluation", "feedback=${report.feedback.map { it.priority to it.title }}")
    }

    private class Capture(val startMs:Long,val generation:Long,val startEpochMs:Long) {
        val sessionId=java.util.UUID.randomUUID().toString()
        val sink=DiskCaptureQueue()
        var writer:Job?=null
        var archive:TripArchive?=null
        var finalEnd:Long?=null
        @Volatile var failure:Exception?=null
        var environmentJob:Job?=null
        var tickJob:Job?=null
        val availability=linkedMapOf<String,SensorAvailability>()
        val telemetryCount=java.util.concurrent.atomic.AtomicInteger()
        var speedCount=0;var angleCount=0;var olderSamples=0
        var gapStart:Long?=null;var steeringGapStart:Long?=null
        var lastSpeedAt:Long?=null;var lastSteeringAt:Long?=null
        var status="속도 관측 대기 중";var steeringStatus="조향각 관측 대기 중"
    }
}
