package com.noonmaru.routy.pleos.feature.home

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.noonmaru.routy.pleos.BuildConfig
import com.noonmaru.routy.pleos.ui.RoutyTheme
import java.net.URI

@Composable
fun RoutyHomeScreen(onDiagnostics:()->Unit,model:ConnectionViewModel=viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    var connectionPage by rememberSaveable {mutableStateOf(false)}
    val owner=LocalLifecycleOwner.current
    DisposableEffect(owner,model) {
        val observer=LifecycleEventObserver {_,event->when(event){Lifecycle.Event.ON_START->model.foreground(true);Lifecycle.Event.ON_STOP->model.foreground(false);else->Unit}}
        owner.lifecycle.addObserver(observer)
        if(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))model.foreground(true)
        onDispose {owner.lifecycle.removeObserver(observer);model.foreground(false)}
    }
    RoutyTheme {
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.safeDrawingPadding()) {
                val compact=maxWidth<1050.dp
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.width(if(compact)150.dp else 190.dp).fillMaxHeight().padding(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            RoadIcon(40.dp);Text("routy",fontSize=32.sp,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.height(38.dp))
                        RailButton("내 연습",!connectionPage){connectionPage=false}
                        RailButton("계정 연결",connectionPage){connectionPage=true}
                        Spacer(Modifier.weight(1f))
                        if(BuildConfig.DEBUG)TextButton(onClick=onDiagnostics,modifier=Modifier.heightIn(min=64.dp)){Text("개발 도구",fontSize=18.sp)}
                        Text("나에게 맞는 길,\n나아지는 운전",fontSize=16.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,lineHeight=25.sp)
                    }
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(if(compact)28.dp else 48.dp),verticalArrangement=Arrangement.spacedBy(30.dp)) {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                            Text(if(connectionPage)"계정 연결" else "내 연습",fontSize=28.sp,fontWeight=FontWeight.SemiBold)
                            Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=RoundedCornerShape(50)) {
                                Row(Modifier.padding(horizontal=20.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                    Canvas(Modifier.size(8.dp)){drawCircle(Color(0xff175f55))}
                                    Text(if(state.connected)"계정 연결됨" else "연결 대기",fontSize=18.sp,color=MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        if(state.error!=null)Surface(color=Color(0xffffeeea),shape=RoundedCornerShape(16.dp)) {
                            Row(Modifier.fillMaxWidth().padding(20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                                Text(state.error!!,Modifier.weight(1f),fontSize=20.sp,color=MaterialTheme.colorScheme.error)
                                TextButton(onClick=model::refresh){Text("다시 확인",fontSize=20.sp)}
                            }
                        }
                        if(connectionPage)ConnectionPage(state,compact,model::issue) else HomePage(state,compact){connectionPage=true}
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

@Composable private fun RailButton(label:String,selected:Boolean,onClick:()->Unit) {
    Surface(color=if(selected)MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,shape=RoundedCornerShape(18.dp),
        modifier=Modifier.fillMaxWidth().heightIn(min=76.dp).semantics {stateDescription=if(selected)"선택됨" else ""}.clickable(role=Role.Button,onClick=onClick)) {
        Box(Modifier.padding(horizontal=12.dp,vertical=20.dp),contentAlignment=Alignment.CenterStart){Text(label,fontSize=22.sp,fontWeight=if(selected)FontWeight.Bold else FontWeight.Normal,color=if(selected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}

@Composable private fun HomePage(state:ConnectionState,compact:Boolean,onConnect:()->Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical=18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(30.dp)) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(24.dp)) {
            Text(if(state.connected)"${state.ownerName}님의 운전 연습" else "나의 속도로 익숙해지는 운전",fontSize=22.sp,color=MaterialTheme.colorScheme.primary)
            Text(if(state.connected)"연습의 순간을,\n배움의 기록으로." else "나에게 맞는 길을,\n나아지는 운전을.",fontSize=if(compact)42.sp else 54.sp,lineHeight=if(compact)55.sp else 70.sp,fontWeight=FontWeight.Bold,letterSpacing=(-1).sp)
            Text("차량에서는 연습에 집중하고,\n주행 후에는 웹에서 차분히 돌아보세요.",fontSize=24.sp,lineHeight=38.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick=onConnect,modifier=Modifier.heightIn(min=80.dp),shape=RoundedCornerShape(16.dp),contentPadding=PaddingValues(horizontal=32.dp,vertical=20.dp)) {
                Text(if(state.connected)"연결 상태 보기" else "계정 연결하기",fontSize=24.sp,fontWeight=FontWeight.SemiBold)
            }
        }
        if(!compact)RouteArt(Modifier.width(400.dp).height(330.dp))
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(24.dp)) {
        InfoPanel("함께 연습할 계정",if(state.connected)"${state.ownerName}님과 연결됐어요" else "스마트폰·웹과 한 번 연결해보세요",if(state.connected)"주행 기록을 내 계정에서 이어볼 수 있어요." else "차량에서는 로그인 정보를 입력하지 않아도 돼요.",Modifier.weight(1f))
        InfoPanel("내 주행 기록",if(state.sent>0)"저장된 기록 ${state.sent}건" else "첫 연습의 기록을 기다려요",if(state.pending+state.failed>0)"전송 대기 ${state.pending}건 · 확인 필요 ${state.failed}건" else "완료된 기록은 인터넷 연결 후 자동으로 전송돼요.",Modifier.weight(1f))
    }
    Text("준비하고, 달리고, 돌아보며 조금씩 익숙해져요.",fontSize=20.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun InfoPanel(label:String,title:String,detail:String,modifier:Modifier) {
    Surface(modifier,shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(28.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text(label,fontSize=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title,fontSize=26.sp,fontWeight=FontWeight.SemiBold,lineHeight=36.sp)
            Text(detail,fontSize=20.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,lineHeight=32.sp)
        }
    }
}

@Composable private fun ConnectionPage(state:ConnectionState,compact:Boolean,onIssue:()->Unit) {
    if(compact)Column(verticalArrangement=Arrangement.spacedBy(30.dp)){ConnectionInstructions(state,onIssue);CodePanel(state,Modifier.fillMaxWidth())}
    else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(44.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)){ConnectionInstructions(state,onIssue)}
        CodePanel(state,Modifier.width(380.dp))
    }
}

@Composable private fun ConnectionInstructions(state:ConnectionState,onIssue:()->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(24.dp)) {
        Text(if(state.connected)"연결이 완료됐어요" else "스마트폰도, 노트북도\n간편하게 연결해요.",fontSize=44.sp,lineHeight=58.sp,fontWeight=FontWeight.Bold,letterSpacing=(-1).sp)
        Text(if(state.connected)"${state.ownerName}님의 계정에서\n주행 기록과 다음 연습을 이어보세요." else "주차한 상태에서 QR을 스캔하거나,\n웹에서 연결 코드를 입력해주세요.",fontSize=24.sp,lineHeight=38.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(!state.connected) {
            Step(1,"연결 코드를 발급해주세요")
            Step(2,"웹에서 계정과 차량을 확인해주세요")
            Step(3,"연결을 완료하면 기록이 함께 모여요")
            Button(onClick=onIssue,enabled=!state.loading,modifier=Modifier.heightIn(min=80.dp),shape=RoundedCornerShape(16.dp),contentPadding=PaddingValues(28.dp,18.dp)) {
                Text(if(state.loading)"코드 발급 중…" else if(state.challenge!=null)"새 코드 발급" else "연결 코드 발급",fontSize=23.sp,fontWeight=FontWeight.SemiBold)
            }
            Text("새 코드를 발급하면 이전 코드는 사용할 수 없어요.",fontSize=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,lineHeight=28.sp)
        }
    }
}

@Composable private fun Step(number:Int,title:String) {
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(18.dp)) {
        Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=RoundedCornerShape(50),modifier=Modifier.size(40.dp)) {
            Box(contentAlignment=Alignment.Center){Text(number.toString(),fontSize=20.sp,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)}
        }
        Text(title,fontSize=22.sp,lineHeight=33.sp)
    }
}

@Composable private fun CodePanel(state:ConnectionState,modifier:Modifier) {
    Surface(modifier,shape=RoundedCornerShape(28.dp),color=MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(32.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(20.dp)) {
            when {
                state.connected -> {
                    Canvas(Modifier.padding(top=30.dp).size(96.dp)) {
                        drawCircle(Color(0xffd9f1e9));val path=Path().apply{moveTo(size.width*.28f,size.height*.5f);lineTo(size.width*.45f,size.height*.67f);lineTo(size.width*.74f,size.height*.34f)}
                        drawPath(path,Color(0xff175f55),style=Stroke(width=6.dp.toPx(),cap=StrokeCap.Round))
                    }
                    Text("${state.ownerName}님",fontSize=34.sp,fontWeight=FontWeight.Bold)
                    Text("계정 연결 완료",fontSize=22.sp,color=MaterialTheme.colorScheme.primary)
                    Text("이제 웹에서 주행 기록을\n확인할 수 있어요.",fontSize=21.sp,lineHeight=33.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(25.dp))
                }
                state.challenge!=null && state.seconds>0 -> {
                    val challenge=state.challenge
                    val image=remember(challenge.uri) {
                        val matrix=PairingQr.matrix(challenge.uri)
                        val pixels=IntArray(matrix.width*matrix.height){i->if(matrix[i%matrix.width,i/matrix.width])android.graphics.Color.BLACK else android.graphics.Color.WHITE}
                        Bitmap.createBitmap(pixels,matrix.width,matrix.height,Bitmap.Config.ARGB_8888).asImageBitmap()
                    }
                    Image(image,"스마트폰 카메라로 스캔할 계정 연결 QR",modifier=Modifier.size(260.dp))
                    Text(challenge.code.chunked(4).joinToString(" "),fontSize=42.sp,fontWeight=FontWeight.Bold,letterSpacing=3.sp,modifier=Modifier.semantics {contentDescription="연결 코드 "+challenge.code.toCharArray().joinToString(" ")})
                    Text("남은 시간 ${state.seconds/60}:${(state.seconds%60).toString().padStart(2,'0')}",fontSize=22.sp,color=MaterialTheme.colorScheme.primary)
                    val address=remember(challenge.uri){URI(challenge.uri).let {it.host+(if(it.port!=-1)":${it.port}" else "")}}
                    Text(address,fontSize=19.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("QR 대신 웹의 ‘차량 연결’에서\n코드를 입력해도 괜찮아요.",fontSize=18.sp,lineHeight=29.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    RoadIcon(82.dp)
                    Text(if(state.challenge==null)"연결할 준비가 됐어요" else "코드 사용 시간이 지났어요",fontSize=27.sp,fontWeight=FontWeight.Bold,lineHeight=39.sp)
                    Text(if(state.challenge==null)"코드를 발급하면 여기에\nQR과 연결 코드가 표시돼요." else "‘새 코드 발급’을 선택해\n다시 연결해주세요.",fontSize=22.sp,lineHeight=36.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(30.dp))
                }
            }
        }
    }
}

@Composable private fun RoadIcon(size:androidx.compose.ui.unit.Dp) {
    val color=MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(size)) {
        drawRoundRect(color,cornerRadius=androidx.compose.ui.geometry.CornerRadius(size.toPx()*.28f))
        val p=Path().apply {moveTo(this@Canvas.size.width*.33f,this@Canvas.size.height*.76f);lineTo(this@Canvas.size.width*.33f,this@Canvas.size.height*.38f);cubicTo(this@Canvas.size.width*.33f,this@Canvas.size.height*.14f,this@Canvas.size.width*.7f,this@Canvas.size.height*.14f,this@Canvas.size.width*.7f,this@Canvas.size.height*.38f);cubicTo(this@Canvas.size.width*.7f,this@Canvas.size.height*.58f,this@Canvas.size.width*.46f,this@Canvas.size.height*.5f,this@Canvas.size.width*.46f,this@Canvas.size.height*.65f);lineTo(this@Canvas.size.width*.7f,this@Canvas.size.height*.76f)}
        drawPath(p,Color.White,style=Stroke(width=this.size.width*.08f,cap=StrokeCap.Round))
        drawCircle(Color(0xffe8b85a),radius=this.size.width*.07f,center=Offset(this.size.width*.7f,this.size.height*.76f))
    }
}

@Composable private fun RouteArt(modifier:Modifier) {
    Canvas(modifier) {
        drawOval(Color(0xffe5eee5))
        val path=Path().apply {moveTo(size.width*.1f,size.height*.78f);cubicTo(size.width*.38f,size.height*.78f,size.width*.17f,size.height*.2f,size.width*.49f,size.height*.2f);cubicTo(size.width*.8f,size.height*.2f,size.width*.59f,size.height*.8f,size.width*.87f,size.height*.48f)}
        drawPath(path,Color(0xffbdd8cc),style=Stroke(width=56.dp.toPx(),cap=StrokeCap.Round))
        drawPath(path,Color.White,style=Stroke(width=2.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(),10.dp.toPx()))))
        for((position,color) in listOf(Offset(.1f,.78f) to Color(0xff175f55),Offset(.49f,.2f) to Color(0xffe8b85a),Offset(.87f,.48f) to Color(0xff175f55))) {
            val point=Offset(size.width*position.x,size.height*position.y);drawCircle(Color.White,16.dp.toPx(),point);drawCircle(color,11.dp.toPx(),point)
        }
    }
}
