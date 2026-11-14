package com.noonmaru.routy.pleos.feature.trip

import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noonmaru.routy.pleos.feature.evaluation.EvaluationState

@Composable fun PracticePanel() {
    val context=LocalContext.current
    val access by DrivingAccess.state.collectAsStateWithLifecycle()
    val trip by TripStore.state.collectAsStateWithLifecycle()
    fun open(){context.startActivity(Intent(context,DrivingActivity::class.java))}
    fun start(){if(DrivingAccess.state.value.parked){TripService.start(context);open()}}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted ->if(granted)start()}
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(28.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text(if(trip.active)"운전 연습을 기록하고 있어요" else "오늘의 운전 연습",fontSize=28.sp,fontWeight=FontWeight.SemiBold)
            Text(if(trip.phase=="SAVED")"기록을 저장했어요. 웹에서 복습할 수 있어요." else "화면을 벗어나도 기록은 계속돼요. 이번 연습은 최대 10분간 기록합니다.",fontSize=22.sp)
            (trip.evaluation as? EvaluationState.Unavailable)?.let {Text(it.message,fontSize=22.sp,color=MaterialTheme.colorScheme.error)}
            Button(enabled=access.parked,onClick={if(trip.active)open() else if(context.checkSelfPermission("android.car.permission.CAR_SPEED")==PackageManager.PERMISSION_GRANTED)start() else permission.launch("android.car.permission.CAR_SPEED")},modifier=Modifier.heightIn(min=80.dp)) {
                Text(if(trip.active)"연습 상태 보기" else "운전 연습 시작",fontSize=24.sp)
            }
        }
    }
}
