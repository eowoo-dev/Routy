package org.eowoo.routy.pleos.data.telemetry

import android.os.SystemClock
import org.eowoo.routy.evaluation.*
import java.net.HttpURLConnection
import java.net.URL

/** Debug-only local proxy; never substitutes UTC or receipt time for the frame timestamp. */
class EnvironmentSource {
    private var lastRun: String? = null
    private var lastSequence = -1L
    private fun get(path: String): JsonObject {
        val connection=URL("http://127.0.0.1:8090/routy/$path").openConnection() as HttpURLConnection
        connection.connectTimeout=500; connection.readTimeout=500
        return try { JsonDocuments.read(connection.inputStream.bufferedReader().use { it.readText() }) } finally { connection.disconnect() }
    }
    fun poll(): AlignedEnvironmentFrame? {
        if(!org.eowoo.routy.pleos.BuildConfig.DEBUG) return null
        val sent=SystemClock.elapsedRealtime()
        val clock=get("clock")["serverAtMs"] as Number
        val received=SystemClock.elapsedRealtime()
        val mapping=EnvironmentClockMapping.probe(sent,received,clock.toLong())
        val raw=get("model")["frame"] as? Map<*,*> ?: return null
        val frame=EnvironmentEvaluation.decode(JsonDocuments.write(mapOf("frames" to listOf(raw)))).single()
        if(lastRun==frame.runId && frame.sequence<=lastSequence) return null
        lastRun=frame.runId; lastSequence=frame.sequence
        val generated=(raw["generatedAtMs"] as? Number)?.toLong() ?: return null
        val at=generated+mapping.offsetMs
        val now=SystemClock.elapsedRealtime()
        if(at>now+mapping.uncertaintyMs || now-at>500 || at<0) return null
        return AlignedEnvironmentFrame(frame,at,mapping.uncertaintyMs,generated)
    }
}
