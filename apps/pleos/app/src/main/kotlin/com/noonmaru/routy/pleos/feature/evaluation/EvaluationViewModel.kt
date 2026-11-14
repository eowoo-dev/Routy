package com.noonmaru.routy.pleos.feature.evaluation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.noonmaru.routy.pleos.feature.trip.*
import kotlinx.coroutines.flow.*

/** Diagnostics facade. Destroying this screen never cancels a service-owned drive. */
class EvaluationViewModel(application:Application):AndroidViewModel(application) {
    private val local=EvaluationController(application,viewModelScope)
    private val live=MutableStateFlow(TripStore.state.value.active)
    val state=combine(local.state,TripStore.state,live){debug,trip,useLive -> if(useLive)trip.evaluation else debug}
        .stateIn(viewModelScope,SharingStarted.Eagerly,EvaluationState.Idle)
    val scenarioStatus=local.scenarioStatus
    val uploadStatus=local.uploadStatus
    fun startCapture(){live.value=true;TripService.start(getApplication(),30_000)}
    fun retryUploads()=local.retryUploads()
    fun permissionDenied()=local.permissionDenied()
    fun cancelCapture()=local.cancelCapture()
    private fun debug(action:()->Unit){if(!TripStore.state.value.active){live.value=false;action()}}
    fun watchScenarios()=debug(local::watchScenarios)
    fun replaySavedScenario()=debug(local::replaySavedScenario)
    fun analyzeEnvironment()=debug(local::analyzeEnvironment)
    fun replayExample()=debug(local::replayExample)
    override fun onCleared(){local.close();super.onCleared()}
}
