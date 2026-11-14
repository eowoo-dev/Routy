package com.noonmaru.routy.pleos.data.records

import com.noonmaru.routy.evaluation.*
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Durable per-part receipts; retries repeat only an unacknowledged part. */
object ArchiveTransfer {
    interface Server {
        fun part(index:Int,bytes:ByteArray,sha256:String):JsonObject
        fun complete(record:JsonObject,archive:JsonObject):JsonObject
    }
    fun send(queue:RecordOutbox,item:RecordOutbox.Item,file:File,server:Server,stopped:()->Boolean={false}) {
        val archive=checkNotNull(item.archive)
        require(archive.parts>0 && archive.nextPart in 0..archive.parts && archive.bytes>0 &&
            (archive.bytes+TripArchive.PART_BYTES-1)/TripArchive.PART_BYTES==archive.parts.toLong())
        require(file.length()==archive.bytes && TripArchive.sha256(file)==archive.sha256) { "상세 평가 원본이 없거나 손상됐습니다" }
        RandomAccessFile(file,"r").use { input ->
            input.seek(archive.nextPart.toLong()*TripArchive.PART_BYTES)
            for(index in archive.nextPart until archive.parts) {
                if(stopped())throw java.io.InterruptedIOException("전송을 이어서 재개합니다")
                val bytes=ByteArray(minOf(TripArchive.PART_BYTES.toLong(),input.length()-input.filePointer).toInt())
                input.readFully(bytes)
                val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
                val receipt=server.part(index,bytes,hash)
                require((receipt["index"] as? Number)?.toInt()==index && receipt["sha256"]==hash) { "상세 평가 조각 응답이 다릅니다" }
                queue.progress(item.sessionId,index+1)
            }
        }
        if(stopped())throw java.io.InterruptedIOException("전송을 이어서 재개합니다")
        val receipt=server.complete(item.payload,mapOf("parts" to archive.parts,"bytes" to archive.bytes,"sha256" to archive.sha256))
        queue.sent(item.sessionId,receipt)
    }
}
