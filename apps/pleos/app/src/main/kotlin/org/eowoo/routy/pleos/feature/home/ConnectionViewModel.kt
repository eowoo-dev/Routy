package org.eowoo.routy.pleos.feature.home

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.eowoo.routy.contracts.api.DefaultApi
import org.eowoo.routy.contracts.model.VehicleInstallationRegistration
import org.eowoo.routy.contracts.model.VehicleConnection
import org.eowoo.routy.pleos.BuildConfig
import org.eowoo.routy.pleos.data.records.RecordSync
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

data class PairingCode(val code:String,val uri:String,val deadlineMs:Long)
data class ConnectionState(val loading:Boolean=false,val connected:Boolean=false,val ownerName:String="",val challenge:PairingCode?=null,
    val seconds:Int=0,val error:String?=null,val sent:Int=0,val pending:Int=0,val failed:Int=0)
class ConnectionViewModel(application:Application):AndroidViewModel(application) {
    private val current=MutableStateFlow(ConnectionState())
    val state=current.asStateFlow()
    private var watching:Job?=null
    private var checking:Job?=null
    private var issuing:Job?=null
    private fun api():DefaultApi {
        val (id,secret)=RecordSync.credentials(getApplication())
        val client=OkHttpClient.Builder().callTimeout(8,TimeUnit.SECONDS).connectTimeout(3,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
            .addInterceptor {chain->chain.proceed(chain.request().newBuilder().header("Authorization","Bearer $id.$secret").build())}.build()
        return DefaultApi(BuildConfig.API_BASE_URL,client).also {it.registerVehicleInstallation(VehicleInstallationRegistration(id,secret))}
    }
    fun foreground(active:Boolean) {
        watching?.cancel();watching=null
        if(!active){checking?.cancel();return}
        refresh()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {RecordSync.outbox(getApplication()).items()}.onSuccess {rows->withContext(Dispatchers.Main){current.value=current.value.copy(sent=rows.count{it.state=="SENT"},pending=rows.count{it.state=="PENDING"},failed=rows.count{it.state=="FAILED"})}}
        }
        watching=viewModelScope.launch {
            var ticks=0
            while(isActive){
                val challenge=current.value.challenge
                if(challenge!=null){val seconds=((challenge.deadlineMs-SystemClock.elapsedRealtime()+999)/1000).coerceAtLeast(0).toInt();current.value=current.value.copy(seconds=seconds);if(seconds>0 && ++ticks%3==0)refresh()}
                delay(1000)
            }
        }
    }
    fun refresh() {
        if(checking?.isActive==true)return
        checking=viewModelScope.launch {
            try {
                val value=withContext(Dispatchers.IO){api().getVehicleConnection()}
                val linked=value.status==VehicleConnection.Status.connected
                current.value=current.value.copy(connected=linked,ownerName=value.ownerName.orEmpty(),challenge=if(linked)null else current.value.challenge,error=null)
            }catch(e:CancellationException){throw e}catch(_:Exception){current.value=current.value.copy(error="인터넷 연결을 확인한 뒤 다시 시도해주세요.")}
        }
    }
    fun issue() {
        if(issuing?.isActive==true)return
        current.value=current.value.copy(loading=true,challenge=null,error=null)
        issuing=viewModelScope.launch {
            try {
                val value=withContext(Dispatchers.IO){api().createVehiclePairing()}
                current.value=current.value.copy(loading=false,challenge=PairingCode(value.code,value.verificationUri.toString(),SystemClock.elapsedRealtime()+value.expiresInSeconds*1000L),seconds=value.expiresInSeconds,error=null)
            }catch(e:CancellationException){throw e}catch(_:Exception){current.value=current.value.copy(loading=false,error="연결 코드를 발급하지 못했어요. 인터넷 연결을 확인하고 다시 시도해주세요.");refresh()}
        }
    }
}
