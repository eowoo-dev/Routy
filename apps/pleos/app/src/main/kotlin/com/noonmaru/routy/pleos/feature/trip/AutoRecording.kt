package com.noonmaru.routy.pleos.feature.trip

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object AutoRecording {
    private val current=MutableStateFlow(false)
    val enabled=current.asStateFlow()
    private var initialized=false
    fun initialize(context:Context) {
        if(initialized)return
        current.value=context.getSharedPreferences("driving-preferences",Context.MODE_PRIVATE).getBoolean("auto-record",false)
        initialized=true
    }
    fun setEnabled(context:Context,value:Boolean) {
        initialize(context)
        if(!DrivingAccess.state.value.parked)return
        if(value && context.checkSelfPermission("android.car.permission.CAR_SPEED")!=PackageManager.PERMISSION_GRANTED)return
        if(!context.getSharedPreferences("driving-preferences",Context.MODE_PRIVATE).edit().putBoolean("auto-record",value).commit())return
        current.value=value
        if(value)TripService.arm(context) else TripService.disarm(context)
    }
    /** Called only by visible Activities. No boot receiver or background cold-start bypass. */
    fun armWhenVisible(context:Context) {
        initialize(context)
        if(current.value && context.checkSelfPermission("android.car.permission.CAR_SPEED")==PackageManager.PERMISSION_GRANTED)TripService.arm(context)
    }
}
