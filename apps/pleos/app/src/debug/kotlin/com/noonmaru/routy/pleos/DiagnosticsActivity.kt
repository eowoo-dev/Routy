package com.noonmaru.routy.pleos
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.noonmaru.routy.pleos.feature.diagnostics.DiagnosticsScreen
/** Only packaged in debug; the launcher always opens the user-facing home. */
class DiagnosticsActivity:VehicleActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        setContent { DiagnosticsScreen(vehicleStatus=vehicleStatus) }
    }
}
