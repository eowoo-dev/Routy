package org.eowoo.routy.pleos.data.telemetry

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

data class SteeringSample(val receivedAtMs: Long, val angleDeg: Double?)

/** SDK provides no sensor timestamp. Each observation is explicitly callback-receipt-timed. */
class VehicleSteeringSource(context: Context) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var active: Session? = null

    fun start(onSample: (SteeringSample) -> Unit, onStatus: (String) -> Unit, onGap: () -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        stop()
        if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            onGap(); onStatus("조향각 권한이 없어 미평가합니다."); return
        }
        val session = Session(onSample, onStatus, onGap)
        active = session
        try {
            session.vehicle = Vehicle(context).also { it.initialize() }
            session.status("조향각 지원 범위 확인 중…")
            session.vehicle!!.getSteeringWheel().checkSteeringWheelAngleCapabilities(
                onComplete = { _, bounds -> main.post {
                    if (active !== session) return@post
                    val minimum = bounds.minValue?.toDouble()
                    val maximum = bounds.maxValue?.toDouble()
                    if (minimum == null || maximum == null || !minimum.isFinite() || !maximum.isFinite() || minimum > maximum) {
                        session.failed(IllegalStateException("조향각 지원 범위를 확인할 수 없습니다."))
                    } else {
                        session.minimum = minimum; session.maximum = maximum
                        try {
                            // Snapshot is useful for diagnostics, but is NOT a freshly timestamped sample.
                            session.vehicle!!.getSteeringWheel().getCurrentSteeringWheelAngle(
                                onComplete = { value -> main.post {
                                    if (active === session) session.status("조향각 초기 조회 ${value}° · 표본은 변화 콜백에서만 기록합니다.")
                                } }, onFailed = session::failed)
                            session.vehicle!!.getSteeringWheel().registerSteeringWheelAngle(session.listener)
                            session.registered = true
                            session.status("조향각 변화 구독 중 · 범위 $minimum~$maximum° · 콜백 수신 시각 사용")
                        } catch (exception: Exception) { session.failed(exception) }
                    }
                } },
                onFailed = session::failed,
            )
        } catch (exception: Exception) { session.failed(exception) }
    }

    fun stop() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val session = active ?: return
        active = null
        if (session.registered) runCatching { session.vehicle?.getSteeringWheel()?.unregisterSteeringWheelAngle(session.listener) }
            .onFailure { Log.w("RoutySteering", "Unsubscribe failed", it) }
        runCatching { session.vehicle?.release() }.onFailure { Log.w("RoutySteering", "Release failed", it) }
        session.vehicle = null
    }

    private inner class Session(val onSample: (SteeringSample) -> Unit, val onStatus: (String) -> Unit, val onGap: () -> Unit) {
        var vehicle: Vehicle? = null
        var registered = false
        var minimum = Double.NEGATIVE_INFINITY
        var maximum = Double.POSITIVE_INFINITY
        fun status(message: String) { if (active === this) { onStatus(message); Log.i("RoutySteering", message) } }
        fun failed(exception: Exception) { main.post {
            if (active === this) {
                onGap(); status("조향각 수집 실패: ${exception.message ?: exception.javaClass.simpleName}")
                Log.w("RoutySteering", "Steering unavailable", exception)
            }
        } }
        val listener = object : SteeringWheelAngleListener {
            override fun onSteeringWheelAngleUpdated(angle: Float?) {
                val received = SystemClock.elapsedRealtime()
                main.post {
                    if (active !== this@Session) return@post
                    val valid = angle?.toDouble()?.takeIf { it.isFinite() && it in minimum..maximum }
                    if (valid == null) { onGap(); status("조향각 불가용 또는 지원 범위 밖 값: 해당 구간은 미평가입니다.") }
                    onSample(SteeringSample(received, valid))
                }
            }
            override fun onFailed(e: Exception) = failed(e)
        }
    }

    companion object { const val PERMISSION = "pleos.car.permission.CAR_DRIVE" }
}
