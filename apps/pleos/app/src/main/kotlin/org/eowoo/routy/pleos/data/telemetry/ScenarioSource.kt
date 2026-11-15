package org.eowoo.routy.pleos.data.telemetry

import org.eowoo.routy.evaluation.*
import java.net.HttpURLConnection
import java.net.URL

/** Read-only client: the console remains the sole owner of scenario controls. */
class ScenarioSource {
    private fun get(path:String):String {
        val connection=URL("http://127.0.0.1:8090/routy/$path").openConnection() as HttpURLConnection
        connection.connectTimeout=3000; connection.readTimeout=3000
        return try {
            require(connection.responseCode==200) { "콘솔 응답 ${connection.responseCode}" }
            connection.inputStream.bufferedReader().use {
                val text=it.readTextBounded(4_194_304)
                text
            }
        } finally { connection.disconnect() }
    }
    private fun java.io.Reader.readTextBounded(limit:Int):String {
        val result=StringBuilder(); val buffer=CharArray(8192)
        while(true) { val count=read(buffer); if(count<0)break; require(result.length+count<=limit) { "시나리오 기록 용량 초과" }; result.append(buffer,0,count) }
        return result.toString()
    }
    fun state():ScenarioCompletion = ScenarioCompletion.decode(get("scenarios/state"))
    fun completed(before:ScenarioCompletion):List<EnvironmentFrame> {
        require(before.status=="completed") { "완료된 시나리오가 아닙니다" }
        val frames=EnvironmentEvaluation.decode(get("trace"))
        val after=state()
        ScenarioCompletion.verify(before,after,frames)
        return frames
    }
}
