package com.noonmaru.routy.pleos

import ai.pleos.playground.vehicle.Vehicle
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.noonmaru.routy.pleos.feature.diagnostics.DiagnosticsScreen

class MainActivity : ComponentActivity() {
    private var vehicle: Vehicle? = null
    private var vehicleStatus by mutableStateOf("SDK 초기화 대기")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DiagnosticsScreen(vehicleStatus = vehicleStatus) }
    }

    override fun onStart() {
        super.onStart()
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
