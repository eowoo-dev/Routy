package com.noonmaru.routy.pleos.feature.trip

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.AtomicFile
import com.noonmaru.routy.evaluation.JsonDocuments
import com.noonmaru.routy.pleos.R
import com.noonmaru.routy.pleos.feature.evaluation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

data class TripState(val active:Boolean=false,val sessionId:String?=null,val startedMs:Long=0,val phase:String="IDLE",val evaluation:EvaluationState=EvaluationState.Idle)
object TripStore {
    internal val current=MutableStateFlow(TripState())
    val state=current.asStateFlow()
}

/** Explicitly user-started AAOS sensor acquisition. No Activity, binding, or screen lifecycle owner. */
class TripService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private lateinit var recorder:EvaluationController
    private var starting=false
    private var observer:Job?=null
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onCreate() {
        super.onCreate()
        DrivingAccess.connect(this)
        recorder=EvaluationController(application,scope)
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==STOP){if(DrivingAccess.state.value.parked)recorder.finishCapture();return START_NOT_STICKY}
        if(starting || TripStore.state.value.active)return START_NOT_STICKY
        starting=true
        try {showNotification()}catch(e:Exception){fail("수집 알림을 시작하지 못했어요.");return START_NOT_STICKY}
        scope.launch {
            val access=withTimeoutOrNull(5000){DrivingAccess.state.first {it.known && it.gear!=null}}
            if(access?.parked!=true || checkSelfPermission("android.car.permission.CAR_SPEED")!=PackageManager.PERMISSION_GRANTED){fail("주차 상태와 차량 센서 권한을 확인해주세요.");return@launch}
            val duration=intent?.getLongExtra("durationMs",600_000L)?.takeIf {it in 5_000..600_000}?:600_000L
            try {
                recorder.startCapture(duration)
                TripStore.current.value=TripState(true,recorder.currentSessionId,SystemClock.elapsedRealtime(),"RECORDING",recorder.state.value)
                journal()
                observer=launch {
                    recorder.state.collect {value ->
                        TripStore.current.value=TripStore.state.value.copy(evaluation=value)
                        if(value is EvaluationState.Finished || value is EvaluationState.Unavailable){
                            TripStore.current.value=TripStore.state.value.copy(active=false,phase=if(value is EvaluationState.Finished)"SAVED" else "FAILED")
                            journal();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
                        }
                    }
                }
                while(TripStore.state.value.active){journal();delay(1000)}
            }catch(e:CancellationException){throw e}catch(e:Exception){fail("평가 수집을 시작하지 못했어요: ${e.message}")}
        }
        return START_NOT_STICKY
    }
    private fun showNotification() {
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("drive-evaluation","주행 연습 기록",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,DrivingActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=Notification.Builder(this,"drive-evaluation").setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("루티가 운전 연습을 기록하고 있어요").setContentText("주차 후 기록을 마칠 수 있어요.")
            .setContentIntent(open).setOngoing(true).build()
        if(Build.VERSION.SDK_INT>=34)startForeground(74,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(74,notification)
    }
    private fun fail(message:String) {
        TripStore.current.value=TripStore.state.value.copy(active=false,phase="FAILED",evaluation=EvaluationState.Unavailable(message))
        runCatching {journal()};stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
    }
    private fun journal() {
        val trip=TripStore.state.value;val access=DrivingAccess.state.value
        val recording=trip.evaluation as? EvaluationState.Recording
        val file=AtomicFile(File(noBackupFilesDir,"active-trip.json"))
        val value=JsonDocuments.write(mapOf("sessionId" to trip.sessionId,"phase" to trip.phase,"startedMs" to trip.startedMs,
            "updatedMs" to SystemClock.elapsedRealtime(),"samples" to recording?.samples,"uxKnown" to access.known,
            "requiresOptimization" to access.requiresOptimization,"restrictions" to access.restrictions,"gear" to access.gear))
        val output=file.startWrite();try{output.write(value.toByteArray(Charsets.UTF_8));file.finishWrite(output)}catch(e:Exception){file.failWrite(output);throw e}
    }
    override fun onDestroy() {
        if(TripStore.state.value.active){TripStore.current.value=TripStore.state.value.copy(active=false,phase="INTERRUPTED",evaluation=EvaluationState.Unavailable("수집이 중단됐어요. 완료 기록으로 저장하지 않았어요."));runCatching {journal()}}
        recorder.close();scope.cancel();super.onDestroy()
    }
    companion object {
        private const val STOP="com.noonmaru.routy.STOP_TRIP"
        fun start(context:Context,durationMs:Long=600_000) {
            try {context.startForegroundService(Intent(context,TripService::class.java).putExtra("durationMs",durationMs))}
            catch(_:Exception){TripStore.current.value=TripState(phase="FAILED",evaluation=EvaluationState.Unavailable("연습 화면을 연 뒤 다시 시작해주세요."))}
        }
        fun finish(context:Context){if(TripStore.state.value.active)context.startService(Intent(context,TripService::class.java).setAction(STOP))}
    }
}
