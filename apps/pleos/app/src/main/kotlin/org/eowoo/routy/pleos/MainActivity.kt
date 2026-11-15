package org.eowoo.routy.pleos

import ai.pleos.playground.vehicle.Vehicle
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.content.Intent
import org.eowoo.routy.pleos.feature.home.RoutyHomeScreen
import org.eowoo.routy.pleos.data.records.RecordSync

open class VehicleActivity : ComponentActivity() {
    private var vehicle: Vehicle? = null
    protected var vehicleStatus by mutableStateOf("SDK 초기화 대기")

    override fun onStart() {
        super.onStart()
        org.eowoo.routy.pleos.feature.trip.AutoRecording.armWhenVisible(this)
        RecordSync.schedule(applicationContext)
        try {
            vehicle = Vehicle(applicationContext).also { it.initialize() }
            vehicleStatus = "SDK 초기화 요청 완료 · 차량 데이터는 아직 조회하지 않습니다."
            Log.i("RoutyVehicle", "SDK initialization requested: ${BuildConfig.VEHICLE_SDK_VERSION}")
        } catch (exception: Exception) {
            vehicleStatus = "SDK 초기화 실패: ${exception.message ?: exception.javaClass.simpleName}"
            Log.e("RoutyVehicle", vehicleStatus, exception)
        }
    }

    override fun onStop() {
        try {
            vehicle?.release()
        } catch (exception: Exception) {
            Log.w("RoutyVehicle", "SDK release failed", exception)
        } finally {
            vehicle = null
            super.onStop()
        }
    }
}


class MainActivity:VehicleActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        org.eowoo.routy.pleos.feature.trip.DrivingAccess.connect(this)
        enableEdgeToEdge()
        setContent { RoutyHomeScreen(onDiagnostics={
            if(BuildConfig.DEBUG)startActivity(Intent().setClassName(this,"org.eowoo.routy.pleos.DiagnosticsActivity"))
        }) }
    }
}
