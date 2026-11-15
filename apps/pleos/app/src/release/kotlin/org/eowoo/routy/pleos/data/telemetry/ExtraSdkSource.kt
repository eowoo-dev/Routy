package org.eowoo.routy.pleos.data.telemetry

import android.content.Context
import org.eowoo.routy.evaluation.SensorAvailability
import org.eowoo.routy.evaluation.SensorReading

/** Emulator-wide SDK discovery is excluded from release builds. */
class ExtraSdkSource(@Suppress("UNUSED_PARAMETER") context: Context) {
    fun start(@Suppress("UNUSED_PARAMETER") reading: (SensorReading) -> Unit,
        @Suppress("UNUSED_PARAMETER") status: (SensorAvailability) -> Unit) = Unit
    fun stop() = Unit
}
