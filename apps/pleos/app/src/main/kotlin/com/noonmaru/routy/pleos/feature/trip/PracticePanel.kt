package com.noonmaru.routy.pleos.feature.trip

import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noonmaru.routy.pleos.feature.evaluation.EvaluationState

@Composable fun PracticePanel() {
    val context=LocalContext.current
    val access by DrivingAccess.state.collectAsStateWithLifecycle()
    val trip by TripStore.state.collectAsStateWithLifecycle()
    val enabled by AutoRecording.enabled.collectAsStateWithLifecycle()
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted ->if(granted)AutoRecording.setEnabled(context,true)}
    fun change(value:Boolean){
        if(value && context.checkSelfPermission("android.car.permission.CAR_SPEED")!=PackageManager.PERMISSION_GRANTED)permission.launch("android.car.permission.CAR_SPEED")
        else AutoRecording.setEnabled(context,value)
    }
    Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(28.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("운전 연습 자동 기록",fontSize=28.sp)
            Text("D단에서 기록을 시작해요. R·N단에서도 이어서 기록하고, P단이 10초 유지되면 저장해요.",fontSize=22.sp)
            Row(Modifier.fillMaxWidth().heightIn(min=80.dp).toggleable(value=enabled,enabled=access.parked && (!trip.active || trip.automatic),role=Role.Switch,onValueChange=::change),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                Text(if(enabled)"자동 기록 끄기" else "자동 기록 켜기",fontSize=24.sp)
                Switch(checked=enabled,onCheckedChange=null)
            }
            Text(when {
                trip.phase=="PARK_WAIT" -> "주차 확인 중이에요. P단 10초 유지 후 저장해요."
                trip.phase=="SAVING" -> "연습 기록을 평가하고 저장하고 있어요."
                trip.active -> "운전 연습을 기록하고 있어요."
                trip.phase=="SAVED" -> "기록을 저장했어요. 웹에서 복습할 수 있어요."
                enabled && trip.armed -> "자동 기록 대기 중 · D단으로 전환하면 시작해요."
                enabled -> "자동 기록 대기를 준비하고 있어요."
                else -> "자동 기록이 꺼져 있어요."
            },fontSize=22.sp)
            Text("시간 제한 없이 P단이 10초 유지될 때까지 기록해요. 기록 중 끄면 현재 기록을 저장하고 자동 기록을 종료해요.",fontSize=18.sp)
            (trip.evaluation as? EvaluationState.Unavailable)?.let {Text(it.message,fontSize=22.sp,color=MaterialTheme.colorScheme.error)}
            if(trip.active)Button(onClick={context.startActivity(Intent(context,DrivingActivity::class.java))},modifier=Modifier.heightIn(min=80.dp)){Text("연습 상태 보기",fontSize=24.sp)}
        }
    }
}
