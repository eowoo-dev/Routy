package org.eowoo.routy.pleos.data.telemetry

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.eowoo.routy.evaluation.ReadingKind
import org.eowoo.routy.evaluation.SensorAccess
import org.eowoo.routy.evaluation.SensorAvailability
import org.eowoo.routy.evaluation.SensorReadPolicy
import org.eowoo.routy.evaluation.SensorReading
import org.eowoo.routy.pleos.BuildConfig
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Emulator/debug read inventory, modeled on the official sample's public-interface explorer.
 * Fixed domain and operation allowlists, no private reflection, no SET/move/control methods.
 * Production rules should consume typed validated channels, not assume every catalog value is useful.
 */
class VehicleSensorCatalog(context: Context) {
    private val context = context.applicationContext
    private var session: Session? = null

    fun start(onReading: (SensorReading) -> Unit, onAvailability: (SensorAvailability) -> Unit) {
        stop()
        if (!BuildConfig.DEBUG) { onAvailability(SensorAvailability("Vehicle.catalog", SensorAccess.SKIPPED, "Broad discovery is debug-only")); return }
        val s = Session(onReading, onAvailability)
        session = s
        try {
            s.vehicle = Vehicle(context).also { it.initialize() }
            val slots = Semaphore(6)
            val pending = mutableListOf<Triple<String, Any, Pair<List<Method>, Method>>>()
            SensorReadPolicy.domains.forEach { domain ->
              try {
                val api = Class.forName("ai.pleos.playground.vehicle.api.$domain")
                val target = Vehicle::class.java.getMethod("get$domain").invoke(s.vehicle) ?: error("Domain unavailable")
                val methods = api.methods.filter { Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) &&
                    !it.isAnnotationPresent(java.lang.Deprecated::class.java) && !it.isAnnotationPresent(Deprecated::class.java) }
                methods.filter { it.name.startsWith("get") || it.name.startsWith("has") || it.name.startsWith("is") }.groupBy { it.name }.values.map { overloads -> overloads.minBy { it.parameterCount } }.forEach readLoop@ { read ->
                    val key = "Vehicle.$domain.${read.name}"
                    if (!SensorReadPolicy.allowed(domain, read.name)) { s.status(key, SensorAccess.SKIPPED, "Outside the public read/sensor allowlist"); return@readLoop }
                    s.status(key, SensorAccess.PROBING, "Checking capability")
                    pending += Triple(domain, target, methods to read)
                }
              } catch (e: Exception) { s.status("Vehicle.$domain", SensorAccess.ERROR, e.message.orEmpty()) }
            }
            pending.sortedBy { when (it.first) { "Brake" -> 0; "TurnSignal" -> 1; "DrivingMode" -> 2; "SteeringWheel" -> 3; else -> 4 } }
                .forEach { (domain, target, descriptor) ->
                    val (methods, read) = descriptor
                    s.scope.launch { slots.withPermit { s.probe(domain, target, methods, read, "Vehicle.$domain.${read.name}") } }
                }
        } catch (e: Exception) { s.status("Vehicle.catalog", SensorAccess.ERROR, e.message.orEmpty()) }
    }

    fun stop() {
        val s = session ?: return
        session = null
        s.scope.cancel()
        s.cleanups.reversed().forEach { runCatching(it).onFailure { e -> Log.w("RoutySensors", "Listener cleanup failed", e) } }
        s.cleanups.clear()
        runCatching { s.vehicle?.release() }
    }

    private inner class Session(val reading: (SensorReading) -> Unit, val availability: (SensorAvailability) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var vehicle: Vehicle? = null
        val cleanups = mutableListOf<() -> Unit>()
        private val states = mutableMapOf<String, SensorAccess>()
        fun status(key: String, state: SensorAccess, detail: String) {
            if (session !== this) return
            val actual = if (states[key] == SensorAccess.LIVE && state == SensorAccess.READABLE) SensorAccess.LIVE else state
            if (states[key] == actual) return
            states[key] = actual
            availability(SensorAvailability(key, actual, detail.take(300)))
            Log.i("RoutySensors", "$key $actual ${detail.take(180)}")
        }
        fun emit(key: String, kind: ReadingKind, value: Any?, at: Long = SystemClock.elapsedRealtime()) {
            if (session !== this) return
            val observation = encodeReading(key, at, kind, value)
            reading(observation)
            if (!observation.available) status(key, SensorAccess.ERROR, "Null or non-finite reading")
            else status(key, if (kind == ReadingKind.UPDATE) SensorAccess.LIVE else SensorAccess.READABLE,
                if (kind == ReadingKind.UPDATE) "Actual listener callback received" else "Current-value response; receipt time only")
        }
        suspend fun probe(domain: String, target: Any, methods: List<Method>, read: Method, key: String) {
            try {
                val feature = SensorReadPolicy.feature(read.name)
                val check = methods.filter { SensorReadPolicy.allowed(domain, it.name) && it.name.startsWith("check") &&
                    SensorReadPolicy.feature(it.name) == feature }.sortedByDescending { it.parameterTypes.firstOrNull()?.simpleName == "Function2" }.firstOrNull()
                if (check == null) { status(key, SensorAccess.SKIPPED, "No matching public capability contract"); return }
                var capability = callbackCall(target, check, emptyList())
                // Initial service binding may outlive a single RPC timeout. Retry timeouts only.
                var retries = 0
                while (capability == null && retries++ < 2) {
                    delay(500)
                    capability = callbackCall(target, check, emptyList())
                }
                if (capability == null) { status(key, SensorAccess.TIMEOUT, "Capability callback timeout"); return }
                val config = capability.filterIsInstance<CapabilityConfig>().firstOrNull()
                if (config == null || config.accessType.name !in setOf("READ", "READ_WRITE")) {
                    status(key, SensorAccess.UNSUPPORTED, "Capability does not grant read access: ${config?.accessType}"); return
                }
                val parameters = read.parameterTypes.filterNot { it.name.startsWith("kotlin.jvm.functions.Function") }
                val argumentSets: List<List<Any?>> = when {
                    parameters.isEmpty() -> listOf(emptyList())
                    parameters.size in 1..2 && parameters.first().name.startsWith("ai.pleos.playground.vehicle.api.model.area.") -> {
                        val areas = capability.flatMap { v -> when (v) { is Map<*, *> -> v.keys.toList(); is Iterable<*> -> v.toList(); else -> emptyList() } }
                            .filter { parameters.first().isInstance(it) }.distinct().take(16)
                        if (parameters.size == 1) areas.map { listOf(it) }
                        else if (parameters[1].name == "ai.pleos.playground.vehicle.constant.unit.TemperatureUnit") {
                            val unit = parameters[1].enumConstants?.firstOrNull { (it as? Enum<*>)?.name == "CELSIUS" }
                            if (unit == null) emptyList() else areas.map { listOf(it, unit) }
                        } else emptyList()
                    }
                    else -> emptyList()
                }
                if (argumentSets.isEmpty()) { status(key, SensorAccess.UNSUPPORTED, "No supported areas or unhandled read selector; no argument invented"); return }
                if (parameters.isNotEmpty()) status(key, SensorAccess.SKIPPED, "Area-specific results listed under child channels")
                for (arguments in argumentSets) {
                    val selectorsLabel = arguments.joinToString("|") { argument ->
                        encodeReading("selector", 0, ReadingKind.SNAPSHOT, argument).fields.toSortedMap()
                            .entries.joinToString(",") { "${it.key}=${it.value}" }
                    }
                    val channel = if (arguments.isEmpty()) key else "$key[$selectorsLabel]"
                    val initial = runCatching { callbackCall(target, read, arguments) }
                    if (initial.isFailure) status(channel, SensorAccess.ERROR, initial.exceptionOrNull()?.message.orEmpty())
                    else if (initial.getOrNull() == null) status(channel, SensorAccess.TIMEOUT, "Read callback timeout; listener still attempted")
                    else emit(channel, ReadingKind.SNAPSHOT, initial.getOrNull()?.lastOrNull())
                    val register = methods.firstOrNull { SensorReadPolicy.allowed(domain, it.name) && it.name.startsWith("register") && SensorReadPolicy.feature(it.name) == feature }
                    val unregister = register?.let { reg -> methods.firstOrNull { it.name == "un${reg.name}" && it.parameterTypes.contentEquals(reg.parameterTypes) } }
                    if (register != null && unregister != null) {
                        val selectors = register.parameterTypes.dropLast(1)
                        val listenerArgs = selectors.mapNotNull { type -> arguments.firstOrNull { type.isInstance(it) } }
                        if (listenerArgs.size == selectors.size) subscribe(target, register, unregister, listenerArgs, channel)
                    }
                    // Query current discrete states at 1Hz, explicitly SNAPSHOT, never hardware samples.
                    if (domain in setOf("Brake", "TurnSignal") || read.name == "getCruiseControlState") scope.launch {
                        while (isActive) {
                            delay(1_000)
                            runCatching { callbackCall(target, read, arguments) }.onSuccess { result ->
                                if (result != null) emit(channel, ReadingKind.SNAPSHOT, result.lastOrNull()) else invalidate(channel, "Current state query timeout")
                            }.onFailure { invalidate(channel, it.message.orEmpty()) }
                        }
                    }
                }
            } catch (e: Exception) {
                val cause = if (e is InvocationTargetException) e.targetException else e
                val message = cause.message ?: cause.javaClass.simpleName
                status(key, when {
                    cause is SecurityException || message.contains("PERMISSION", true) -> SensorAccess.PERMISSION_DENIED
                    message.contains("NOT_SUPPORTED", true) -> SensorAccess.UNSUPPORTED
                    else -> SensorAccess.ERROR
                }, message)
            }
        }
        private fun invalidate(key: String, message: String) {
            emit(key, ReadingKind.GAP, null)
            status(key, SensorAccess.ERROR, message)
        }
        private fun subscribe(target: Any, register: Method, unregister: Method, values: List<Any?>, key: String) {
            val listenerType = register.parameterTypes.lastOrNull() ?: return
            if (!listenerType.isInterface || !listenerType.name.startsWith("ai.pleos.playground.vehicle.listener.") || register.parameterCount != values.size + 1) return
            val listener = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { proxy, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "Routy read listener $key"
                    else -> {
                        val at = SystemClock.elapsedRealtime()
                        scope.launch {
                            if (method.name == "onFailed") invalidate(key, (args?.firstOrNull() as? Throwable)?.message ?: "Listener failed")
                            else if (method.name.startsWith("on")) emit(key, ReadingKind.UPDATE, args?.lastOrNull(), at)
                        }
                        null
                    }
                }
            }
            val args = (values + listener).toTypedArray()
            register.invoke(target, *args)
            cleanups += { unregister.invoke(target, *args); Unit }
            if (states[key] != SensorAccess.LIVE) status(key, SensorAccess.SUBSCRIBED, "Listener registered; waiting for actual update")
        }
        private suspend fun callbackCall(target: Any, method: Method, values: List<Any?>): List<Any?>? = withTimeoutOrNull(1_200) {
            suspendCancellableCoroutine { continuation ->
                fun complete(value: List<Any?>) { if (continuation.isActive) continuation.resume(value) }
                val success: Any = when (method.parameterTypes.getOrNull(values.size)?.simpleName) {
                    "Function1" -> { a: Any? -> complete(listOf(a)) }
                    "Function2" -> { a: Any?, b: Any? -> complete(listOf(a, b)) }
                    "Function3" -> { a: Any?, b: Any?, c: Any? -> complete(listOf(a, b, c)) }
                    else -> { continuation.resumeWithException(IllegalArgumentException("Unsupported public callback shape")); return@suspendCancellableCoroutine }
                }
                val failed: (Exception) -> Unit = { if (continuation.isActive) continuation.resumeWithException(it) }
                try { method.invoke(target, *(values + success + failed).toTypedArray()) }
                catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            }
        }
    }
}
