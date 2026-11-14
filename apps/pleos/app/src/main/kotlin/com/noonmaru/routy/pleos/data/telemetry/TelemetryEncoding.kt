package com.noonmaru.routy.pleos.data.telemetry

import com.noonmaru.routy.evaluation.ReadingKind
import com.noonmaru.routy.evaluation.SensorReading
import java.lang.reflect.Modifier

/** Bounded, public data-model getters only. Never reflects over SDK service/manager internals. */
internal fun encodeReading(key: String, at: Long, kind: ReadingKind, value: Any?): SensorReading {
    val fields = linkedMapOf<String, String>()
    fun flatten(path: String, item: Any?, depth: Int) {
        if (fields.size >= 64) return
        when {
            item == null -> fields[path] = "null"
            item is Enum<*> -> fields[path] = item.name
            item is Number || item is Boolean || item is String -> fields[path] = item.toString().take(256)
            depth >= 3 -> fields[path] = "[depth limit]"
            item is Map<*, *> -> item.entries.take(32).forEach { flatten("$path.${it.key}", it.value, depth + 1) }
            item is Iterable<*> -> item.take(32).forEachIndexed { index, v -> flatten("$path[$index]", v, depth + 1) }
            item.javaClass.isArray -> (0 until minOf(32, java.lang.reflect.Array.getLength(item))).forEach { flatten("$path[$it]", java.lang.reflect.Array.get(item, it), depth + 1) }
            item.javaClass.name.startsWith("ai.pleos.playground.") && !item.javaClass.name.contains("service") -> {
                val getters = item.javaClass.methods.filter { !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                    (it.name.startsWith("get") || it.name.startsWith("is")) && it.name != "getClass" }.sortedBy { it.name }.take(32)
                getters.forEach { getter -> runCatching { getter.invoke(item) }.onSuccess { flatten("$path.${getter.name.removePrefix("get")}", it, depth + 1) } }
            }
            else -> fields[path] = "[unmapped public value: ${item.javaClass.simpleName}]"
        }
    }
    flatten("value", value, 0)
    val number = (value as? Number)?.toDouble()?.takeIf { it.isFinite() }
    return SensorReading(key, at, kind, number, value as? Boolean, (value as? Enum<*>)?.name, fields,
        available = value != null && (value !is Number || number != null))
}
