package org.eowoo.routy.pleos.data.telemetry

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import org.eowoo.routy.evaluation.*
import java.lang.reflect.Proxy

/** Public location/navigation observation only; never requests/cancels a route. */
class ExtraSdkSource(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var fused: FusedLocation? = null
    private var navi: NaviHelper? = null
    private var locationListener: FusedLocationListener? = null
    private var naviListener: NaviHelperEventListener? = null
    private var generation = 0

    fun start(reading: (SensorReading) -> Unit, status: (SensorAvailability) -> Unit) {
        stop()
        val token = generation
        fun active(block: () -> Unit) { handler.post { if (token == generation) block() } }
        status(SensorAvailability("ADAS.SDK", SensorAccess.NO_PUBLIC_SDK, "No public ADAS artifact in the current Playground Maven catalog; no private binder access"))
        try {
            val sdk = FusedLocation(context).also { fused = it }
            when {
                !sdk.isSupported() -> status(SensorAvailability("FusedLocation", SensorAccess.UNSUPPORTED, "SDK isSupported() returned false"))
                context.checkSelfPermission("pleos.car.permission.FUSED_LOCATION") != PackageManager.PERMISSION_GRANTED ->
                    status(SensorAvailability("FusedLocation", SensorAccess.PERMISSION_DENIED, "Location permission not granted"))
                else -> {
                    sdk.initialize()
                    var received = false
                    val listener = object : FusedLocationListener {
                        override fun onChanged(location: LocationData?) {
                            val at = SystemClock.elapsedRealtime()
                            active {
                                received = true
                                reading(encodeReading("FusedLocation.location", at, ReadingKind.UPDATE, location).copy(providerTimestampMs = location?.timestampMillis))
                                status(SensorAvailability("FusedLocation", if (location == null) SensorAccess.ERROR else SensorAccess.LIVE, "Callback received; native timestamp retained, alignment uses receipt time"))
                            }
                        }
                        override fun onError(exception: Exception) = active {
                            reading(SensorReading("FusedLocation.location", SystemClock.elapsedRealtime(), ReadingKind.GAP, available = false))
                            status(SensorAvailability("FusedLocation", SensorAccess.ERROR, exception.message.orEmpty()))
                        }
                    }
                    locationListener = listener
                    sdk.registerFusedLocationCallback(listener)
                    sdk.getLastKnownLocation(object : FusedLocationListener {
                        override fun onChanged(location: LocationData?) {
                            val at = SystemClock.elapsedRealtime()
                            active { reading(encodeReading("FusedLocation.lastKnown", at, ReadingKind.SNAPSHOT, location).copy(providerTimestampMs = location?.timestampMillis)) }
                        }
                        override fun onError(exception: Exception) = active {
                            status(SensorAvailability("FusedLocation.lastKnown", SensorAccess.ERROR, exception.message.orEmpty()))
                        }
                    })
                    status(SensorAvailability("FusedLocation", SensorAccess.SUBSCRIBED, "Waiting for actual callback"))
                    handler.postDelayed({ if (token == generation && !received) status(SensorAvailability("FusedLocation", SensorAccess.TIMEOUT, "No location callback within 8 seconds")) }, 8_000)
                }
            }
        } catch (e: Exception) { status(SensorAvailability("FusedLocation", SensorAccess.ERROR, e.message.orEmpty())) }
        try {
            val sdk = NaviHelper(context).also { navi = it }
            var received = false
            val allowedEvents = setOf("onCurrentLocationInfo", "onRouteStateInfo", "onCurrentRoadSpeedLimit", "onDrivingInfo", "onNavigationStatus", "onTBTInfo", "onCameraAlert", "onCautionPointInfo")
            val listener = Proxy.newProxyInstance(NaviHelperEventListener::class.java.classLoader,
                arrayOf(NaviHelperEventListener::class.java)) { proxy, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "Routy public navigation observer"
                    else -> {
                        val at = SystemClock.elapsedRealtime()
                        active {
                            if (method.name in allowedEvents) {
                                received = true
                                reading(encodeReading("NaviHelper.${method.name}", at, ReadingKind.UPDATE, args?.firstOrNull()))
                                status(SensorAvailability("NaviHelper.${method.name}", SensorAccess.LIVE, "Actual navigation event; native fields/units retained"))
                            } else if (method.name == "onError") status(SensorAvailability("NaviHelper", SensorAccess.ERROR, args?.firstOrNull().toString()))
                        }
                        null
                    }
                }
            } as NaviHelperEventListener
            naviListener = listener
            status(SensorAvailability("NaviHelper", SensorAccess.PROBING, "Initializing public SDK"))
            sdk.initialize(onConnected = { active {
                sdk.addListener(listener)
                status(SensorAvailability("NaviHelper", SensorAccess.SUBSCRIBED, "Listener registered; read-only current location/route/TBT requests"))
                sdk.getCurrentLocationInfo(); sdk.getRouteStateInfo(); sdk.getTBTInfo()
            } }, onFailed = { e -> active { status(SensorAvailability("NaviHelper", SensorAccess.ERROR, e.message.orEmpty())) } })
            handler.postDelayed({ if (token == generation && !received) status(SensorAvailability("NaviHelper", SensorAccess.TIMEOUT, "No navigation event within 8 seconds; navigation may be inactive")) }, 8_000)
        } catch (e: Exception) { status(SensorAvailability("NaviHelper", SensorAccess.ERROR, e.message.orEmpty())) }
    }

    fun stop() {
        generation++
        handler.removeCallbacksAndMessages(null)
        runCatching { locationListener?.let { fused?.unregisterFusedLocationCallback(it) } }
        runCatching { naviListener?.let { navi?.removeListener(it) } }
        runCatching { fused?.release() }; runCatching { navi?.release() }
        fused = null; navi = null; locationListener = null; naviListener = null
    }
}
