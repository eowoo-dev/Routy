package org.eowoo.routy.pleos.feature.diagnostics

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.eowoo.routy.pleos.data.HealthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface HealthState {
    data object Loading : HealthState
    data class Ready(val summary: String) : HealthState
    data class Failed(val message: String) : HealthState
}

class DiagnosticsViewModel : ViewModel() {
    private val repository = HealthRepository()
    private val mutableHealth = MutableStateFlow<HealthState>(HealthState.Loading)
    val health = mutableHealth.asStateFlow()

    init { checkHealth() }

    fun checkHealth() {
        mutableHealth.value = HealthState.Loading
        viewModelScope.launch {
            try {
                val response = repository.getHealth()
                val summary = "${response.service} · ${response.status} · ${response.timestamp}"
                mutableHealth.value = HealthState.Ready(summary)
                Log.i("RoutyHealth", "API health OK: $summary")
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                val message = exception.message ?: exception.javaClass.simpleName
                mutableHealth.value = HealthState.Failed(message)
                Log.w("RoutyHealth", "API health failed", exception)
            }
        }
    }
}
