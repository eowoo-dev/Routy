package com.noonmaru.routy.pleos.feature.trip

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noonmaru.routy.pleos.feature.evaluation.EvaluationState
import com.noonmaru.routy.pleos.ui.RoutyTheme
import com.noonmaru.routy.pleos.MainActivity

/** Only this minimal Activity declares DO. No login, QR, diagnostics, scrolling, or live score. */
class DrivingActivity:ComponentActivity() {
    override fun onStart(){super.onStart();AutoRecording.armWhenVisible(this)}
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge();DrivingAccess.connect(this)
        setContent {
            val access by DrivingAccess.state.collectAsStateWithLifecycle()
            val trip by TripStore.state.collectAsStateWithLifecycle()
            var routed by rememberSaveable {mutableStateOf(false)}
            LaunchedEffect(access.parked) {
                if(access.parked && !trip.active && !routed && intent.action==Intent.ACTION_MAIN){
                    routed=true
                    startActivity(Intent(this@DrivingActivity,MainActivity::class.java))
                }
            }
            BackHandler(enabled=!access.parked) { /* Remain on the limited screen while restricted. */ }
            RoutyTheme {
                Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
                    Column(Modifier.safeDrawingPadding().padding(48.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                        Text(if(trip.active)"운전 연습 기록 중" else if(trip.phase=="SAVED")"연습 기록을 저장했어요" else "운전에 집중해주세요",fontSize=42.sp,fontWeight=FontWeight.Bold)
                        Spacer(Modifier.height(24.dp))
                        Text(if(access.parked && trip.automatic && trip.active)"P단이 10초 유지되면 기록을 저장해요." else if(access.parked)"주차 후 기록을 돌아보세요." else "자세한 기록은 주차 후 확인할 수 있어요.",fontSize=26.sp)
                        if(access.parked) {
                            Spacer(Modifier.height(32.dp))
                            (trip.evaluation as? EvaluationState.Unavailable)?.let {Text(it.message,fontSize=22.sp,color=MaterialTheme.colorScheme.error)}
                            if(trip.active && !trip.automatic)Button(onClick={TripService.finish(this@DrivingActivity)},modifier=Modifier.heightIn(min=80.dp)){Text("연습 마치고 저장",fontSize=24.sp)}
                            else Button(onClick={startActivity(Intent(this@DrivingActivity,MainActivity::class.java))},modifier=Modifier.heightIn(min=80.dp)){Text(if(trip.active)"자동 기록 설정" else "사용자 홈으로",fontSize=24.sp)}
                        }
                    }
                }
            }
        }
    }
}
