package org.eowoo.routy.pleos.data.telemetry

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import org.eowoo.routy.evaluation.*
import org.eowoo.routy.pleos.BuildConfig

/** Public advertised AAOS properties only. Failed access is recorded, never elevated. */
class AaosContextSource(private val context: Context) {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var car: Car? = null
    @Volatile private var generation = 0

    @Suppress("DEPRECATION")
    fun start(reading: (SensorReading) -> Unit, status: (SensorAvailability) -> Unit) {
        stop()
        if (!BuildConfig.DEBUG) return
        val token = generation
        val worker = HandlerThread("RoutyPublicProperties").also { it.start(); thread = it }
        val queue = Handler(worker.looper).also { handler = it }
        val names = VehiclePropertyIds::class.java.fields.filter { it.type == Int::class.javaPrimitiveType }
            .associate { it.getInt(null) to it.name }
        car = Car.createCar(context, queue, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready -> queue.post {
            if (generation != token) return@post
            if (!ready) { status(SensorAvailability("AAOS", SensorAccess.ERROR, "Car service disconnected")); return@post }
            try {
                val manager = connected.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                val configs = manager.propertyList.filter { it.propertyId in names && names[it.propertyId] != "INFO_VIN" && it.access and 1 != 0 }.take(180)
                status(SensorAvailability("AAOS.catalog", SensorAccess.READABLE, "${configs.size} advertised public readable properties; 1Hz snapshots"))
                val denied = mutableSetOf<Int>()
                val reported = mutableSetOf<String>()
                val poll = object : Runnable {
                    override fun run() {
                        if (generation != token) return
                        for (config in configs) {
                            val id = config.propertyId
                            if (id in denied) continue
                            val key = "AAOS.${names[id]}"
                            for (area in config.areaIds.take(16).ifEmpty { listOf(0) }) {
                                val channel = if (area == 0) key else "$key[$area]"
                                try {
                                    val v = manager.getProperty<Any>(id, area)
                                    val at = SystemClock.elapsedRealtime()
                                    val raw = if (v?.status == CarPropertyValue.STATUS_AVAILABLE) v.value else null
                                    var r = encodeReading(channel, at, ReadingKind.SNAPSHOT, raw)
                                        .copy(providerTimestampMs = v?.timestamp?.div(1_000_000))
                                    if (id == VehiclePropertyIds.GEAR_SELECTION) r = r.copy(state = when ((raw as? Number)?.toInt()) {
                                        1 -> "NEUTRAL"; 2 -> "REVERSE"; 4 -> "PARK"; 8 -> "DRIVE"; else -> "UNKNOWN"
                                    })
                                    reading(r)
                                    if (reported.add(channel)) status(SensorAvailability(channel, SensorAccess.READABLE, "1Hz current-state query; provider timestamp preserved separately"))
                                } catch (e: Exception) {
                                    denied += id
                                    reading(SensorReading(channel, SystemClock.elapsedRealtime(), ReadingKind.GAP, available = false))
                                    status(SensorAvailability(channel, if (e is SecurityException) SensorAccess.PERMISSION_DENIED else SensorAccess.ERROR, e.message.orEmpty().take(250)))
                                }
                            }
                        }
                        if (generation == token) queue.postDelayed(this, 1_000)
                    }
                }
                poll.run()
            } catch (e: Exception) { status(SensorAvailability("AAOS.catalog", SensorAccess.ERROR, e.message.orEmpty())) }
        } }
    }

    fun stop() {
        generation++
        handler?.removeCallbacksAndMessages(null)
        val previous = car
        car = null
        handler?.post { runCatching { previous?.disconnect() } }
        thread?.quitSafely()
        handler = null; thread = null
    }
}
