package org.eowoo.routy.pleos.feature.demo

import android.app.Application
import android.util.AtomicFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.eowoo.routy.evaluation.*
import org.eowoo.routy.pleos.data.records.RecordSync
import org.eowoo.routy.pleos.feature.trip.*
import java.io.File
import java.util.UUID

data class SubmissionDemoState(val scenario:SubmissionScenario,val frame:EnvironmentFrame,val elapsedMs:Long=0,
    val phase:String="READY",val report:EvaluationReport?=null,val message:String="시작하면 APK에 포함된 합성 입력을 재생합니다.")

/** No sensor subscriptions or vehicle writes. Separate from the real trip service. */
class SubmissionDemoViewModel(application:Application):AndroidViewModel(application) {
    val scenarios=SubmissionScenarios.all
    private var playback=SubmissionPlayback(scenarios.first(),UUID.randomUUID().toString())
    private val mutableState=MutableStateFlow(SubmissionDemoState(playback.scenario,playback.frame))
    val state=mutableState.asStateFlow()
    private var job:Job?=null

    fun select(id:String) {
        if(job?.isActive==true)return
        playback=SubmissionPlayback(scenarios.single { it.id==id },UUID.randomUUID().toString())
        mutableState.value=SubmissionDemoState(playback.scenario,playback.frame)
    }
    fun reset(){if(mutableState.value.phase=="ANALYZING")return;pause();select(playback.scenario.id)}
    fun pause() {
        if(playback.phase!=SubmissionPlayback.Phase.RUNNING)return
        job?.cancel();playback.pause()
        mutableState.value=mutableState.value.copy(phase="PAUSED",message="시연 일시 정지 · 이어서 시작하거나 초기화할 수 있어요. 완료 전에는 저장하지 않습니다.")
    }
    fun start() {
        if(job?.isActive==true)return
        if(!DrivingAccess.state.value.parked || AutoRecording.enabled.value || TripStore.state.value.active) {
            mutableState.value=mutableState.value.copy(message="주차 상태에서 실제 자동 기록을 끈 뒤 합성 시연을 시작해주세요.")
            return
        }
        if(playback.phase !in setOf(SubmissionPlayback.Phase.READY,SubmissionPlayback.Phase.PAUSED))return
        playback.start()
        mutableState.value=mutableState.value.copy(phase="RUNNING",message="합성 데이터 재생 중 · 실제 차량을 제어하거나 센서를 수집하지 않습니다.")
        job=viewModelScope.launch {
            try {
                while(playback.phase==SubmissionPlayback.Phase.RUNNING) {
                    delay(50)
                    playback.step()
                    mutableState.value=mutableState.value.copy(frame=playback.frame,elapsedMs=playback.elapsedMs)
                }
                mutableState.value=mutableState.value.copy(phase="ANALYZING",message="완료된 합성 입력을 평가하고 저장하고 있어요.")
                val frames=playback.completedFrames()
                val report=withContext(Dispatchers.Default) { DrivingEvaluationEngine().evaluate(ScenarioReview.session(frames)) }
                val message=withContext(Dispatchers.IO) {
                    val document=ScenarioReview.recording(frames,report)+mapOf("scenarioId" to playback.scenario.id,"sessionId" to playback.runId)
                    val file=AtomicFile(File(getApplication<Application>().filesDir,"submission-demos/latest.json").also { it.parentFile?.mkdirs() })
                    val stream=file.startWrite()
                    try {stream.write(JsonDocuments.write(document).toByteArray(Charsets.UTF_8));file.finishWrite(stream)}
                    catch(e:Exception){file.failWrite(stream);throw e}
                    runCatching {
                        val app=getApplication<Application>()
                        RecordSync.outbox(app).enqueue(playback.runId,report,System.currentTimeMillis()-(report.endMs-report.startMs),playback.scenario.id)
                        RecordSync.schedule(app)
                        "합성 결과를 기기에 저장하고 서버 전송 대기에 추가했어요. 계정 연결 후 웹에서도 합성 기록으로 확인할 수 있어요."
                    }.getOrElse { "합성 결과를 기기에 저장했어요. 서버 전송 준비 실패: ${it.message}" }
                }
                mutableState.value=mutableState.value.copy(phase="COMPLETED",report=report,message=message)
            } catch(e:CancellationException){throw e}
            catch(e:Exception) {
                mutableState.value=mutableState.value.copy(phase="ERROR",message="합성 시연 분석·저장 실패: ${e.message}. 초기화 후 다시 시도해주세요.")
            }
        }
    }
}
