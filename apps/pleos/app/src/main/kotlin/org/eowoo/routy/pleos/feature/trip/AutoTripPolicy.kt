package org.eowoo.routy.pleos.feature.trip

/** Pure monotonic-time session boundary policy. Unknown input breaks continuous Park evidence. */
class AutoTripPolicy(private val parkHoldMs:Long=10_000) {
    enum class Action { NONE, START, FINISH }
    var parkSinceMs:Long?=null
        private set
    private var driveLatched=false
    fun advance(enabled:Boolean,gear:Int?,recording:Boolean,busy:Boolean,nowMs:Long):Action {
        if(!enabled){parkSinceMs=null;driveLatched=false;return if(recording && !busy)Action.FINISH else Action.NONE}
        if(busy){parkSinceMs=null;return Action.NONE}
        if(recording) {
            if(gear!=4){parkSinceMs=null;return Action.NONE}
            val since=parkSinceMs?:nowMs.also {parkSinceMs=it}
            if(nowMs-since>=parkHoldMs){parkSinceMs=null;driveLatched=false;return Action.FINISH}
            return Action.NONE
        }
        parkSinceMs=null
        if(gear==4)driveLatched=false
        if(gear==8 && !driveLatched){driveLatched=true;return Action.START}
        return Action.NONE
    }
}
