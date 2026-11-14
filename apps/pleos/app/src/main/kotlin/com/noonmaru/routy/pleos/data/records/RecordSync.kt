package com.noonmaru.routy.pleos.data.records

import android.content.Context
import androidx.work.*
import com.noonmaru.routy.contracts.api.DefaultApi
import com.noonmaru.routy.contracts.infrastructure.ClientException
import com.noonmaru.routy.contracts.infrastructure.Serializer
import com.noonmaru.routy.contracts.model.DrivingRecordUpload
import com.noonmaru.routy.contracts.model.VehicleInstallationRegistration
import com.noonmaru.routy.evaluation.*
import com.noonmaru.routy.pleos.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import android.util.Base64

object RecordSync {
    fun outbox(context:Context)=RecordOutbox(File(context.noBackupFilesDir,"driving-records"))
    fun schedule(context:Context,force:Boolean=false) {
        val request=OneTimeWorkRequestBuilder<RecordUploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("driving-record-uploads",if(force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,request)
    }
    fun status(context:Context):String {
        var pending=0;var failed=0;var sent=0;var error:String?=null
        for(row in outbox(context).scan()){
            when(row.state){"PENDING"->pending++;"FAILED"->failed++;"SENT"->sent++}
            if(error==null && row.state!="SENT")error=row.error
        }
        return "서버 전송 · 대기 ${pending}건 / 확인 필요 ${failed}건 / 저장 ${sent}건"+
            (error?.let { "\n$it" } ?: "")
    }
    @Synchronized fun credentials(context:Context):Pair<UUID,String> {
        val store=android.util.AtomicFile(File(context.noBackupFilesDir,"vehicle-installation.json"))
        val text=try { store.openRead().bufferedReader().use { it.readText() } } catch(_:java.io.FileNotFoundException) {
            val secret=ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
            val value=JsonDocuments.write(mapOf("installationId" to UUID.randomUUID().toString(),"secret" to secret))
            val output=store.startWrite();try{output.write(value.toByteArray(Charsets.UTF_8));store.finishWrite(output)}catch(e:Exception){store.failWrite(output);throw e}
            value
        }
        val value=JsonDocuments.read(text)
        return UUID.fromString(value["installationId"] as String) to value["secret"] as String
    }
}

class RecordUploadWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result = withContext(Dispatchers.IO) {
        val queue=RecordSync.outbox(applicationContext)
        fun pending()=queue.scan().filter{it.state=="PENDING"}
        if(pending().firstOrNull()==null)return@withContext Result.success()
        val (id,secret)=RecordSync.credentials(applicationContext)
        val client=OkHttpClient.Builder().callTimeout(150,TimeUnit.SECONDS).connectTimeout(5,TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false)
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization","Bearer $id.$secret").build()) }.build()
        val api=DefaultApi(BuildConfig.API_BASE_URL,client)
        try {
            api.registerVehicleInstallation(VehicleInstallationRegistration(id,secret))
        }catch(e:ClientException) {
            val terminal=e.statusCode !in setOf(408,429)
            pending().forEach { queue.failed(it.sessionId,"설치 인증 응답 ${e.statusCode}",terminal) }
            return@withContext if(terminal)Result.success() else Result.retry()
        }catch(e:java.io.IOException) {pending().forEach { queue.failed(it.sessionId,"서버 연결 대기 · 자동 재시도합니다",false) };return@withContext Result.retry()
        }catch(e:com.noonmaru.routy.contracts.infrastructure.ServerException) {pending().forEach { queue.failed(it.sessionId,"서버 일시 오류 · 자동 재시도합니다",false) };return@withContext Result.retry()}
        var retry=false
        for(item in pending()) {
            if(isStopped)return@withContext Result.retry()
            try {
                if(item.archive!=null){
                    val sessionId=UUID.fromString(item.sessionId).toString()
                    fun request(path:String,method:String,value:JsonObject):JsonObject {
                        val body=JsonDocuments.compact(value).toRequestBody("application/json".toMediaType())
                        val request=Request.Builder().url(BuildConfig.API_BASE_URL+"/api/driving-records/$sessionId/"+path).method(method,body).build()
                        client.newCall(request).execute().use{ response ->
                            if(!response.isSuccessful)throw ArchiveHttpException(response.code)
                            val source=response.body?.source()?:throw java.io.IOException("Empty receipt")
                            source.request(16_385)
                            if(source.buffer.size>16_384)throw java.io.IOException("Receipt too large")
                            return JsonDocuments.read(source.readUtf8())
                        }
                    }
                    ArchiveTransfer.send(queue,item,File(applicationContext.filesDir,"sensor-traces/$sessionId/reports.ndjson"),object:ArchiveTransfer.Server {
                        override fun part(index:Int,bytes:ByteArray,sha256:String)=request("parts/$index","PUT",mapOf("sha256" to sha256,"data" to Base64.encodeToString(bytes,Base64.NO_WRAP)))
                        override fun complete(record:JsonObject,archive:JsonObject)=request("complete","POST",mapOf("record" to record,"archive" to archive))
                    },stopped={isStopped})
                    continue
                }
                val payload=requireNotNull(Serializer.moshi.adapter(DrivingRecordUpload::class.java).fromJson(JsonDocuments.write(item.payload)))
                val receipt=api.putDrivingRecord(UUID.fromString(item.sessionId),payload)
                queue.sent(item.sessionId,mapOf("id" to receipt.id.toString(),"sessionId" to receipt.sessionId.toString(),"receivedAt" to receipt.receivedAt.toString()))
            }catch(e:ArchiveHttpException){
                val terminal=e.status in 400..499 && e.status !in setOf(408,429)
                queue.failed(item.sessionId,"상세 평가 전송 응답 ${e.status}",terminal)
                if(!terminal){retry=true;break}
            }catch(e:ClientException) {
                val terminal=e.statusCode !in setOf(408,429)
                queue.failed(item.sessionId,"서버 응답 ${e.statusCode} · "+if(terminal) "기록 확인 후 다시 시도해주세요" else "자동 재시도합니다",terminal)
                if(!terminal)retry=true
            }catch(e:java.io.IOException) {queue.failed(item.sessionId,"서버 연결 대기 · 자동 재시도합니다",false);retry=true;break
            }catch(e:com.noonmaru.routy.contracts.infrastructure.ServerException) {queue.failed(item.sessionId,"서버 일시 오류 · 자동 재시도합니다",false);retry=true;break
            }catch(e:com.squareup.moshi.JsonDataException) {queue.failed(item.sessionId,"서버 응답 형식 확인 대기 · 자동 재시도합니다",false);retry=true
            }catch(e:IllegalArgumentException) {queue.failed(item.sessionId,"기록 형식 확인이 필요합니다",true)}
        }
        if(retry)Result.retry() else Result.success()
    }
}
private class ArchiveHttpException(val status:Int):Exception()
