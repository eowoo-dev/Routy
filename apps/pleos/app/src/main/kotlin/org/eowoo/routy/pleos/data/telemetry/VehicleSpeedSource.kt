package org.eowoo.routy.pleos.data.telemetry

import android.annotation.SuppressLint
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyConfig
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.TimeUnit

/** Raw speed observation; timestampMs and receivedAtMs both use elapsed realtime, never wall time. */
data class SpeedSample(
    val timestampMs: Long,
    val speedMps: Double?,
    val propertyStatus: Int,
    val receivedAtMs: Long,
    val sourceTimestampNanos: Long,
)

/**
 * Read-only AAOS input adapter for the development evaluation flow.
 *
 * All callbacks are serialized on a private HandlerThread, including Car lifecycle events originally
 * delivered on the main thread. Consumers must dispatch UI updates and return promptly. Call stop()
 * when the owner stops collecting. Each start creates a new session and stops the previous one.
 * There is no polling, generated speed, timestamp replacement, or implicit permission grant.
 */
class VehicleSpeedSource(context: Context) {
    private val context = context.applicationContext

    @Volatile
    private var active: Session? = null

    @Synchronized
    fun start(
        onSample: (SpeedSample) -> Unit,
        onStatus: (String) -> Unit,
        onGap: () -> Unit = {},
    ) {
        stop()
        val session = Session(onSample, onStatus, onGap)
        active = session
        session.handler.post { connect(session) }
    }

    @Synchronized
    fun stop() {
        val previous = active ?: return
        active = null
        previous.handler.post {
            release(previous)
            previous.thread.quitSafely()
        }
    }

    private fun isActive(session: Session) = active === session

    @SuppressLint("MissingPermission") // Permission is checked before creating the session.
    private fun connect(session: Session) {
        if (!isActive(session)) return
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            session.onStatus("AAOS 환경이 아니므로 차량 속도를 수집하지 않습니다.")
            return
        }
        if (context.checkSelfPermission(Car.PERMISSION_SPEED) != PackageManager.PERMISSION_GRANTED) {
            session.onStatus("차량 속도 권한이 필요합니다. 허용 전에는 미평가로 처리합니다.")
            return
        }
        session.onStatus("AAOS 차량 속도 서비스 연결 중…")
        try {
            session.car = Car.createCar(
                context,
                session.handler,
                Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT,
            ) { car, ready ->
                // The Car lifecycle listener always runs on main, regardless of the event handler.
                session.handler.post {
                    if (isActive(session)) {
                        if (ready) subscribe(session, car) else disconnected(session)
                    }
                }
            }
            if (!isActive(session)) release(session)
        } catch (exception: Exception) {
            session.onGap()
            session.onStatus("차량 속도 연결 실패: ${exception.message ?: exception.javaClass.simpleName}")
            release(session)
        } catch (error: LinkageError) {
            session.onGap()
            session.onStatus("이 환경의 android.car API를 사용할 수 없습니다: ${error.javaClass.simpleName}")
            release(session)
        }
    }

    @Suppress("DEPRECATION") // registerCallback is the compatible API on the Pleos API 34 image.
    @SuppressLint("MissingPermission")
    private fun subscribe(session: Session, car: Car) {
        try {
            session.manager?.unregisterCallback(session.callback, SPEED_PROPERTY)
            val manager = car.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            if (manager == null) {
                session.onGap()
                session.onStatus("차량 속도 매니저가 제공되지 않아 미평가로 처리합니다.")
                return
            }
            session.manager = manager
            val config = manager.getCarPropertyConfig(SPEED_PROPERTY)
            if (config == null) {
                session.onGap()
                session.onStatus("PERF_VEHICLE_SPEED 미지원 또는 접근 불가: 속도 평가는 보류합니다.")
                return
            }
            val rate = if (config.changeMode == CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_CONTINUOUS) {
                val minimum = config.minSampleRate
                val maximum = config.maxSampleRate
                if (!minimum.isFinite() || !maximum.isFinite() || minimum < 0f || maximum < minimum) {
                    session.onGap()
                    session.onStatus("차량 속도 수신 주기 설정이 유효하지 않아 수집을 보류합니다.")
                    return
                }
                REQUESTED_RATE_HZ.coerceIn(minimum, maximum)
            } else {
                CarPropertyManager.SENSOR_RATE_ONCHANGE
            }
            if (manager.registerCallback(session.callback, SPEED_PROPERTY, rate)) {
                session.onStatus("차량 속도 구독 중 · 요청 ${rate}Hz · 실제 수신 품질은 별도 확인합니다.")
            } else {
                session.onGap()
                session.onStatus("차량 속도 구독이 거부되어 미평가로 처리합니다.")
            }
        } catch (exception: Exception) {
            session.onGap()
            session.onStatus("차량 속도 구독 실패: ${exception.message ?: exception.javaClass.simpleName}")
        }
    }

    private fun disconnected(session: Session) {
        session.onGap()
        session.manager = null
        session.onStatus("차량 속도 서비스 연결이 끊겼습니다. 재연결 전 구간은 미평가입니다.")
    }

    @Suppress("DEPRECATION")
    private fun release(session: Session) {
        runCatching { session.manager?.unregisterCallback(session.callback, SPEED_PROPERTY) }
            .onFailure { Log.w(TAG, "Speed callback cleanup failed", it) }
        session.manager = null
        runCatching { session.car?.disconnect() }
            .onFailure { Log.w(TAG, "Car disconnect failed", it) }
        session.car = null
    }

    private inner class Session(
        val onSample: (SpeedSample) -> Unit,
        val onStatus: (String) -> Unit,
        val onGap: () -> Unit,
    ) {
        val thread = HandlerThread("RoutyVehicleSpeed").apply { start() }
        val handler = Handler(thread.looper)
        var car: Car? = null
        var manager: CarPropertyManager? = null

        val callback = object : CarPropertyManager.CarPropertyEventCallback {
            @Suppress("DEPRECATION") // getStatus exists on the target API 34 runtime.
            override fun onChangeEvent(event: CarPropertyValue<*>) {
                if (event.propertyId != SPEED_PROPERTY || event.areaId != 0) return
                val receivedAtMs = SystemClock.elapsedRealtime()
                val rawStatus = event.status
                // CarPropertyValue.value is meaningless unless status is AVAILABLE.
                val value = if (rawStatus == CarPropertyValue.STATUS_AVAILABLE) {
                    (event.value as? Number)?.toDouble()?.takeIf { it.isFinite() }
                } else null
                val sample = SpeedSample(
                    timestampMs = TimeUnit.NANOSECONDS.toMillis(event.timestamp),
                    speedMps = value,
                    propertyStatus = rawStatus,
                    receivedAtMs = receivedAtMs,
                    sourceTimestampNanos = event.timestamp,
                )
                handler.post {
                    if (!isActive(this@Session)) return@post
                    if (value == null || event.timestamp <= 0L) {
                        onGap()
                        onStatus("차량 속도 값 또는 시각이 유효하지 않습니다. 해당 구간은 미평가입니다.")
                    }
                    // Preserve the provider timestamp, unavailable status, signed speed and duplicates.
                    onSample(sample)
                }
            }

            override fun onErrorEvent(propertyId: Int, areaId: Int) {
                reportError(propertyId, areaId, null)
            }

            override fun onErrorEvent(propertyId: Int, areaId: Int, errorCode: Int) {
                reportError(propertyId, areaId, errorCode)
            }

            private fun reportError(propertyId: Int, areaId: Int, errorCode: Int?) {
                if (propertyId != SPEED_PROPERTY || areaId != 0) return
                handler.post {
                    if (!isActive(this@Session)) return@post
                    onGap()
                    onStatus("차량 속도 이벤트 오류${errorCode?.let { " ($it)" }.orEmpty()}: 연속 평가 구간을 끊습니다.")
                }
            }
        }
    }

    private companion object {
        const val TAG = "RoutySpeed"
        const val SPEED_PROPERTY = VehiclePropertyIds.PERF_VEHICLE_SPEED
        const val REQUESTED_RATE_HZ = 10f
    }
}
