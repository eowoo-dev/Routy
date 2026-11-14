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

data class TripState(val active:Boolean=false,val sessionId:String?=null,val startedMs:Long=0,val phase:String="IDLE",val evaluation:EvaluationState=EvaluationState.Idle,val armed:Boolean=false,val automatic:Boolean=false,val parkSinceMs:Long?=null)
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
    private var monitor:Job?=null
    private var automatic=false
    private var saving=false
    private val policy=AutoTripPolicy()
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onCreate() {
        super.onCreate()
        AutoRecording.initialize(this)
        DrivingAccess.connect(this)
        recorder=EvaluationController(application,scope)
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==DISARM) {
            automatic=false
            TripStore.current.value=TripStore.state.value.copy(armed=false)
            if(TripStore.state.value.active){finishRecording()} else {journal();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
            return START_NOT_STICKY
        }
        if(intent?.action==STOP){if(DrivingAccess.state.value.parked)finishRecording();return START_NOT_STICKY}
        if(intent?.action==ARM) {
            if(!AutoRecording.enabled.value || (TripStore.state.value.active && !automatic && !TripStore.state.value.automatic))return START_NOT_STICKY
            automatic=true
            TripStore.current.value=TripStore.state.value.copy(armed=true,automatic=true,phase=if(TripStore.state.value.active)TripStore.state.value.phase else if(TripStore.state.value.phase=="SAVED")"SAVED" else "ARMED")
            try {showNotification()}catch(_:Exception){fail("자동 기록 대기를 시작하지 못했어요.");return START_NOT_STICKY}
            journal()
            if(monitor==null)monitor=scope.launch {
                try {
                    // Process gear notifications as well as the timer: a brief R/N cancels Park immediately.
                    launch {
                        try {DrivingAccess.state.collect {stepAutomatic();journal()}}
                        catch(e:CancellationException){throw e}catch(e:Exception){fail("기어 상태 확인 중 오류가 발생했어요: ${e.message}")}
                    }
                    var ticks=0
                    while(isActive){stepAutomatic();if(++ticks%4==0 && TripStore.state.value.active)journal();delay(250)}
                }catch(e:CancellationException){throw e}catch(e:Exception){fail("자동 기록 대기 중 오류가 발생했어요: ${e.message}")}
            }
            return START_NOT_STICKY
        }
        if(starting || TripStore.state.value.active || automatic)return START_NOT_STICKY
        starting=true
        try {showNotification()}catch(_:Exception){fail("수집 알림을 시작하지 못했어요.");return START_NOT_STICKY}
        scope.launch {
            val access=withTimeoutOrNull(5000){DrivingAccess.state.first {it.known && it.gear!=null}}
            if(access?.parked!=true || checkSelfPermission("android.car.permission.CAR_SPEED")!=PackageManager.PERMISSION_GRANTED){fail("주차 상태와 차량 센서 권한을 확인해주세요.");return@launch}
            beginRecording(intent?.getLongExtra("durationMs",30_000L)?.takeIf {it in 5_000..600_000}?:30_000L)
            while(TripStore.state.value.active){journal();delay(1000)}
        }
        return START_NOT_STICKY
    }
    private fun stepAutomatic() {
        val access=DrivingAccess.state.value
        val gear=if(access.known)access.gear else null
        when(policy.advance(AutoRecording.enabled.value,gear,TripStore.state.value.active,saving,SystemClock.elapsedRealtime())) {
            AutoTripPolicy.Action.START -> if(checkSelfPermission("android.car.permission.CAR_SPEED")==PackageManager.PERMISSION_GRANTED)beginRecording(null)
            AutoTripPolicy.Action.FINISH -> finishRecording()
            AutoTripPolicy.Action.NONE -> Unit
        }
        if(!saving && TripStore.state.value.active)TripStore.current.value=TripStore.state.value.copy(phase=if(policy.parkSinceMs!=null)"PARK_WAIT" else "RECORDING",parkSinceMs=policy.parkSinceMs)
    }
    private fun beginRecording(duration:Long?) {
        if(TripStore.state.value.active || saving)return
        try {
            recorder.startCapture(duration)
            TripStore.current.value=TripState(true,recorder.currentSessionId,SystemClock.elapsedRealtime(),"RECORDING",recorder.state.value,automatic,automatic)
            observer?.cancel()
            observer=scope.launch {
                recorder.state.collect {value ->
                    TripStore.current.value=TripStore.state.value.copy(evaluation=value)
                    if(value is EvaluationState.Finished || value is EvaluationState.Unavailable) {
                        saving=false
                        TripStore.current.value=TripStore.state.value.copy(active=false,phase=if(value is EvaluationState.Finished)"SAVED" else "FAILED",parkSinceMs=null)
                        journal()
                        if(!automatic){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()} else showNotification()
                    }
                }
            }
            journal();showNotification()
        }catch(e:Exception){fail("평가 수집을 시작하지 못했어요: ${e.message}")}
    }
    private fun finishRecording() {
        if(!TripStore.state.value.active || saving)return
        saving=true
        TripStore.current.value=TripStore.state.value.copy(phase="SAVING",parkSinceMs=null)
        recorder.finishCapture();journal()
    }
    private fun showNotification() {
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("drive-evaluation","주행 연습 기록",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,DrivingActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=Notification.Builder(this,"drive-evaluation").setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(if(TripStore.state.value.active)"루티가 운전 연습을 기록하고 있어요" else "루티 자동 기록 대기 중").setContentText("D단에서 시작하고 P단 10초 유지 후 저장해요.")
            .setContentIntent(open).setOngoing(true).build()
        if(Build.VERSION.SDK_INT>=34)startForeground(74,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(74,notification)
    }
    private fun fail(message:String) {
        TripStore.current.value=TripStore.state.value.copy(active=false,armed=false,phase="FAILED",evaluation=EvaluationState.Unavailable(message))
        runCatching {journal()};stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
    }
    private fun journal() {
        val trip=TripStore.state.value;val access=DrivingAccess.state.value
        val recording=trip.evaluation as? EvaluationState.Recording
        val file=AtomicFile(File(noBackupFilesDir,"active-trip.json"))
        val value=JsonDocuments.write(mapOf("sessionId" to trip.sessionId,"phase" to trip.phase,"startedMs" to trip.startedMs,
            "updatedMs" to SystemClock.elapsedRealtime(),"armed" to trip.armed,"automatic" to trip.automatic,"autoEnabled" to AutoRecording.enabled.value,"parkSinceMs" to trip.parkSinceMs,"samples" to recording?.samples,"uxKnown" to access.known,
            "requiresOptimization" to access.requiresOptimization,"restrictions" to access.restrictions,"gear" to access.gear))
        val output=file.startWrite();try{output.write(value.toByteArray(Charsets.UTF_8));file.finishWrite(output)}catch(e:Exception){file.failWrite(output);throw e}
    }
    override fun onDestroy() {
        if(TripStore.state.value.active){TripStore.current.value=TripStore.state.value.copy(active=false,phase="INTERRUPTED",evaluation=EvaluationState.Unavailable("수집이 중단됐어요. 완료 기록으로 저장하지 않았어요."));runCatching {journal()}}
        TripStore.current.value=TripStore.state.value.copy(armed=false)
        recorder.close();scope.cancel();super.onDestroy()
    }
    companion object {
        private const val ARM="com.noonmaru.routy.ARM_AUTO"
        private const val DISARM="com.noonmaru.routy.DISARM_AUTO"
        fun arm(context:Context) {
            try {context.startForegroundService(Intent(context,TripService::class.java).setAction(ARM))}
            catch(_:Exception){TripStore.current.value=TripStore.state.value.copy(armed=false,phase="FAILED",evaluation=EvaluationState.Unavailable("앱을 열고 자동 기록을 다시 켜주세요."))}
        }
        fun disarm(context:Context){if(TripStore.state.value.armed || TripStore.state.value.active)context.startService(Intent(context,TripService::class.java).setAction(DISARM))}
        private const val STOP="com.noonmaru.routy.STOP_TRIP"
        fun start(context:Context,durationMs:Long=600_000) {
            try {context.startForegroundService(Intent(context,TripService::class.java).putExtra("durationMs",durationMs))}
            catch(_:Exception){TripStore.current.value=TripState(phase="FAILED",evaluation=EvaluationState.Unavailable("연습 화면을 연 뒤 다시 시작해주세요."))}
        }
        fun finish(context:Context){if(TripStore.state.value.active)context.startService(Intent(context,TripService::class.java).setAction(STOP))}
    }
}
