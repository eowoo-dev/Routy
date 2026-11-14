package com.noonmaru.routy.pleos.feature.trip

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DrivingAccessState(val known:Boolean=false,val requiresOptimization:Boolean=true,val restrictions:Int=-1,val gear:Int?=null) {
    // UX flags are authoritative. Gear is an additional conservative setup prerequisite.
    val parked:Boolean get()=known && !requiresOptimization && restrictions==0 && gear==4
}

/** One process-lifetime, read-only connection. Unknown/disconnected always hides setup. */
object DrivingAccess {
    private val current=MutableStateFlow(DrivingAccessState())
    val state=current.asStateFlow()
    private var car:Car?=null
    private var properties:CarPropertyManager?=null
    private var ux:CarUxRestrictionsManager?=null
    private val listener=CarUxRestrictionsManager.OnUxRestrictionsChangedListener { value ->
        current.value=current.value.copy(known=true,requiresOptimization=value.isRequiresDistractionOptimization,restrictions=value.activeRestrictions)
    }
    private val callback=object:CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value:CarPropertyValue<*>){if(value.propertyId==VehiclePropertyIds.GEAR_SELECTION)current.value=current.value.copy(gear=if(value.status==0)value.value as? Int else null)}
        override fun onErrorEvent(propertyId:Int,areaId:Int){current.value=current.value.copy(gear=null)}
    }
    @Suppress("DEPRECATION")
    @android.annotation.SuppressLint("MissingPermission")
    fun connect(context:Context) {
        if(car!=null)return
        try {
            car=Car.createCar(context.applicationContext,Handler(Looper.getMainLooper()),Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT){connected,ready ->
                current.value=DrivingAccessState()
                if(ready)try {
                    ux=connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                    ux!!.registerListener(listener)
                    listener.onUxRestrictionsChanged(ux!!.currentCarUxRestrictions)
                    properties=connected.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                    current.value=current.value.copy(gear=properties!!.getIntProperty(VehiclePropertyIds.GEAR_SELECTION,0))
                    properties!!.registerCallback(callback,VehiclePropertyIds.GEAR_SELECTION,CarPropertyManager.SENSOR_RATE_ONCHANGE)
                }catch(_:Exception){current.value=DrivingAccessState()}
            }
        }catch(_:Exception){current.value=DrivingAccessState();car=null}catch(_:LinkageError){current.value=DrivingAccessState();car=null}
    }
}
