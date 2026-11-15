package org.eowoo.routy.pleos.feature.demo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.eowoo.routy.evaluation.EnvironmentFrame
import org.eowoo.routy.pleos.feature.evaluation.EnvironmentOverview
import org.eowoo.routy.pleos.feature.trip.AutoRecording
import org.eowoo.routy.pleos.feature.trip.TripStore
import java.util.Locale

@Composable
fun SubmissionDemoPanel(model:SubmissionDemoViewModel=viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val automatic by AutoRecording.enabled.collectAsStateWithLifecycle()
    val trip by TripStore.state.collectAsStateWithLifecycle()
    val owner=LocalLifecycleOwner.current
    DisposableEffect(owner,model) {
        val observer=LifecycleEventObserver { _,event->if(event==Lifecycle.Event.ON_STOP)model.pause() }
        owner.lifecycle.addObserver(observer)
        onDispose {owner.lifecycle.removeObserver(observer);model.pause()}
    }
    LaunchedEffect(automatic,trip.active){if(automatic || trip.active)model.pause()}
    val busy=state.phase in setOf("RUNNING","ANALYZING")
    var menu by remember {mutableStateOf(false)}
    Column(verticalArrangement=Arrangement.spacedBy(20.dp)) {
        Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("합성 데이터 시연 · 실제 주행 평가 아님",fontSize=28.sp,color=MaterialTheme.colorScheme.primary)
                Text("APK에 포함된 가상 차선, 신호등과 앞차 정보를 재생합니다. 실제 도로 감지나 차량 제어 기능이 아닙니다.",fontSize=21.sp)
                Text("인터넷이나 별도 Virtual Console 없이 실행할 수 있어요. 결과와 서버 기록에도 합성 데이터로 표시합니다.",fontSize=18.sp)
            }
        }
        Text("시나리오 선택",fontSize=24.sp)
        model.scenarios.take(4).chunked(2).forEach { row ->
            Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                row.forEach { scenario ->
                    FilterChip(selected=state.scenario.id==scenario.id,onClick={model.select(scenario.id)},enabled=!busy,
                        colors=FilterChipDefaults.filterChipColors(selectedContainerColor=MaterialTheme.colorScheme.surfaceVariant,selectedLabelColor=MaterialTheme.colorScheme.primary),
                        label={Text(scenario.title,fontSize=21.sp)},modifier=Modifier.weight(1f).heightIn(min=72.dp))
                }
            }
        }
        Box {
            OutlinedButton(onClick={menu=true},enabled=!busy,modifier=Modifier.heightIn(min=64.dp)) {Text("실패·경계 사례 선택 (${model.scenarios.size-4}개)",fontSize=20.sp)}
            DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                model.scenarios.drop(4).forEach { scenario ->
                    DropdownMenuItem(text={Text(scenario.title,fontSize=20.sp)},onClick={model.select(scenario.id);menu=false},modifier=Modifier.heightIn(min=64.dp))
                }
            }
        }
        Text(state.scenario.title,fontSize=28.sp)
        Text(state.scenario.description,fontSize=22.sp)
        if(automatic || trip.active)Text("실제 자동 기록을 끈 뒤 합성 시연을 실행해주세요.",fontSize=22.sp,color=MaterialTheme.colorScheme.error)
        Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            Button(onClick=model::start,enabled=!automatic && !trip.active && state.phase in setOf("READY","PAUSED"),modifier=Modifier.heightIn(min=72.dp)) {Text(if(state.phase=="PAUSED")"합성 시연 이어서 시작" else "합성 시연 시작",fontSize=22.sp)}
            OutlinedButton(onClick=model::pause,enabled=state.phase=="RUNNING",modifier=Modifier.heightIn(min=72.dp)){Text("시연 정지",fontSize=22.sp)}
            OutlinedButton(onClick=model::reset,enabled=state.phase!="ANALYZING",modifier=Modifier.heightIn(min=72.dp)){Text("시연 초기화",fontSize=22.sp)}
        }
        Text("재생 ${format(state.elapsedMs/1000.0)} / ${format(state.scenario.durationMs/1000.0)}초 · "+when(state.phase){"RUNNING"->"재생 중";"PAUSED"->"일시 정지";"ANALYZING"->"평가 중";"COMPLETED"->"완료";"ERROR"->"오류";else->"준비"},fontSize=22.sp)
        LinearProgressIndicator(progress={state.elapsedMs.toFloat()/state.scenario.durationMs},color=MaterialTheme.colorScheme.primary,trackColor=MaterialTheme.colorScheme.surfaceVariant,modifier=Modifier.fillMaxWidth())
        Text(state.message,fontSize=20.sp)
        SyntheticRoad(state.frame)
        val f=state.frame
        Text("합성 관측 · 속도 ${format(f.speed*3.6)} km/h · 방향지시등 ${when(f.signal){"LEFT"->"왼쪽";"RIGHT"->"오른쪽";"UNKNOWN"->"미확인";else->"꺼짐"}} · 신호 ${when(f.light){"RED"->"적색";"YELLOW"->"황색";else->"녹색"}}",fontSize=20.sp)
        state.report?.let { report ->
            Surface(color=MaterialTheme.colorScheme.surface,shape=MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Text("합성 데이터 결과 · 실제 주행 평가 아님",fontSize=28.sp,color=MaterialTheme.colorScheme.primary)
                    Text("${state.scenario.title} · ${report.environment?.frames}개 합성 관측을 평가했어요.",fontSize=22.sp)
                    EnvironmentOverview(report)
                }
            }
        }
    }
}

@Composable private fun SyntheticRoad(frame:EnvironmentFrame) {
    val ownColor=MaterialTheme.colorScheme.primary
    val roadColor=MaterialTheme.colorScheme.surfaceVariant
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("가상 도로 미리보기 · 합성 데이터",fontSize=20.sp)
        Canvas(Modifier.fillMaxWidth().height(220.dp).semantics {contentDescription="합성 도로: 자차, 차로 경계, 정지선 및 앞차의 상대 위치"}) {
            drawRect(roadColor)
            val scale=size.width/12f
            val depth=size.height/65f
            val ownY=size.height*.78f
            fun x(d:Double)=size.width/2-(d*scale).toFloat()
            for(d in listOf(-5.55,-1.85,1.85,5.55))drawLine(Color.White,Offset(x(d),0f),Offset(x(d),size.height),4f,
                pathEffect=PathEffect.dashPathEffect(floatArrayOf(18f,12f)))
            val stopY=ownY-((frame.stopLineS-frame.s)*depth).toFloat()
            if(stopY in 0f..size.height)drawLine(if(frame.light=="RED")Color(0xffb44337) else Color.White,Offset(x(5.55),stopY),Offset(x(-5.55),stopY),6f)
            frame.vehicles.forEach { v ->
                val y=ownY-((v.s-frame.s)*depth).toFloat()
                drawRoundRect(Color(0xffb56532),Offset(x(v.d)-24.dp.toPx(),y-36.dp.toPx()),Size(48.dp.toPx(),72.dp.toPx()),CornerRadius(8.dp.toPx()))
            }
            drawRoundRect(ownColor,Offset(x(frame.d)-24.dp.toPx(),ownY-36.dp.toPx()),Size(48.dp.toPx(),72.dp.toPx()),CornerRadius(8.dp.toPx()))
            drawLine(Color.White,Offset(x(frame.d)-14.dp.toPx(),ownY-16.dp.toPx()),Offset(x(frame.d)+14.dp.toPx(),ownY-16.dp.toPx()),4.dp.toPx())
        }
        Text("초록: 자차 · 갈색: 앞차 · 흰색: 차로 경계 · 가로선: 정지선 (위치 개념도, 축척 단순화)",fontSize=18.sp)
    }
}
private fun format(value:Double)=String.format(Locale.ROOT,"%.1f",value)
