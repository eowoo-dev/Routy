package com.noonmaru.routy.pleos.feature.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.noonmaru.routy.pleos.BuildConfig

@Composable
fun DiagnosticsScreen(vehicleStatus: String, model: DiagnosticsViewModel = viewModel()) {
    val health by model.health.collectAsStateWithLifecycle()
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 960.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Text("Routy 개발 환경", style = MaterialTheme.typography.headlineLarge)
                    Text("앱 실행과 서버 연결을 확인하는 개발용 화면입니다.", style = MaterialTheme.typography.bodyLarge)
                    StatusCard("Pleos Vehicle SDK ${BuildConfig.VEHICLE_SDK_VERSION}", vehicleStatus)
                    StatusCard("서버 연결 · ${BuildConfig.API_BASE_URL}", when (val state = health) {
                        HealthState.Loading -> "확인 중…"
                        is HealthState.Ready -> "연결 성공: ${state.summary}"
                        is HealthState.Failed -> "연결 실패: ${state.message}"
                    })
                    Button(
                        onClick = model::checkHealth,
                        enabled = health !is HealthState.Loading,
                        modifier = Modifier.heightIn(min = 64.dp),
                        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 16.dp),
                    ) {
                        Text("서버 연결 다시 확인", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(title: String, description: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(description, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
