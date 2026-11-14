package com.noonmaru.routy.pleos
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.noonmaru.routy.pleos.feature.diagnostics.DiagnosticsScreen
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noonmaru.routy.pleos.feature.trip.DrivingAccess
import com.noonmaru.routy.pleos.feature.trip.DrivingActivity
import android.content.Intent
/** Only packaged in debug; the launcher always opens the user-facing home. */
class DiagnosticsActivity:VehicleActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        DrivingAccess.connect(this)
        setContent {
            val access by DrivingAccess.state.collectAsStateWithLifecycle()
            LaunchedEffect(access.known,access.parked){if(access.known && !access.parked)startActivity(Intent(this@DiagnosticsActivity,DrivingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))}
            if(access.parked)DiagnosticsScreen(vehicleStatus=vehicleStatus)
        }
    }
}
