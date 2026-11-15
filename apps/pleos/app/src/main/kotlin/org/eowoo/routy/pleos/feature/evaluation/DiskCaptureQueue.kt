package org.eowoo.routy.pleos.feature.evaluation

import org.eowoo.routy.evaluation.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicReference

/** Both entry count and encoded size are bounded before crossing the IO boundary. */
class DiskCaptureQueue(capacity:Int=64) {
    data class Row(val atMs:Long,val bytes:ByteArray)
    val rows=Channel<Row>(capacity)
    private val error=AtomicReference<Exception?>()
    val failure:Exception? get()=error.get()
    fun offer(atMs:Long,kind:String,value:JsonObject):Boolean {
        if(failure!=null)return false
        return try {
            val bytes=JsonDocuments.compact(mapOf("kind" to kind,"value" to value)).toByteArray(Charsets.UTF_8)
            require(bytes.size+1<=TripArchive.ROW_BYTES){"센서 관측 한 건의 저장 크기를 초과했습니다"}
            check(rows.trySend(Row(atMs,bytes)).isSuccess){"저장 대기열이 가득 차거나 종료됐습니다"}
            true
        }catch(e:Exception){error.compareAndSet(null,e);false}
    }
    fun close(){rows.close()}
}
