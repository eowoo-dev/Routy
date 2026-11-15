package org.eowoo.routy.evaluation

/** Public SDK boundary. Native heading units/accuracy are not assumed or synthesized. */
object PracticeInputs {
    fun normalize(input: DrivingSession): DrivingSession {
        if (input.telemetryTruncated) return input
        val fused = input.telemetry.filter { it.signal == "FusedLocation.location" && it.kind == ReadingKind.UPDATE }
        val rows = if (fused.size >= 2) fused else input.telemetry.filter { it.signal == "NaviHelper.onCurrentLocationInfo" && it.kind == ReadingKind.UPDATE }
        var previous: GeoPoint? = null
        val positions = if (input.positions.isNotEmpty()) input.positions else rows.map { r ->
            val lat = r.fields["value.Latitude"]?.toDoubleOrNull()
            val lon = r.fields["value.Longitude"]?.toDoubleOrNull()
            val point = if (lat != null && lon != null) GeoPoint(lat,lon).takeIf { it.valid } else null
            val heading = if (point != null && previous != null && distance(previous!!,point) >= 2) bearing(previous!!,point) else null
            previous = point
            Observation(r.atMs,point?.let { PositionFix(it,heading,null,"${r.signal}:coordinate-bearing") },r.available && point != null)
        }.groupBy { it.atMs }.toSortedMap().map { (at,group) ->
            if (group.map { it.value to it.available }.distinct().size == 1) group.first() else Observation<PositionFix>(at,null,false)
        }
        val rawSignals = input.telemetry.filter { it.signal == "Vehicle.TurnSignal.getCurrentTurnSignalState" }.groupBy { it.atMs }.toSortedMap().map { (at,group) ->
            val states = group.map { if (it.available) it.state else null }.distinct()
            val selection = states.singleOrNull()?.let { state -> SignalSelection.entries.firstOrNull { it.name == state } }
            Observation(at,selection,selection != null)
        }
        return input.copy(positions=positions,positionTimeBasis=if (input.positions.isEmpty() && positions.isNotEmpty()) SteeringTimeBasis.CALLBACK_RECEIPT else input.positionTimeBasis,
            signals=if (input.signals.isEmpty()) rawSignals else input.signals,
            signalSemantics=if (input.signals.isEmpty() && rawSignals.isNotEmpty()) SignalSemantics.SELECTION_STATE else input.signalSemantics,
            hazardStateKnown=if (input.signals.isEmpty() && rawSignals.isNotEmpty()) false else input.hazardStateKnown)
    }

    internal fun roads(session: DrivingSession, analysis: PracticeAnalysis): List<Observation<RoadContext>> {
        val observations = analysis.roadMatches.map { match ->
        val road = session.roadNetwork?.segments?.firstOrNull { it.id == match.roadId }
        val context = if (match.status == MatchStatus.MATCHED && match.speedLimitMps != null && road != null)
            RoadContext(road.id,road.id,match.speedLimitMps,minOf(road.validUntilMs,match.atMs+2_000)) else null
            Observation(match.atMs,context,context != null)
        }
        // Invalidate exactly when acquisition failed, even between two position timestamps.
        val invalidations = session.positionGaps.map { Observation<RoadContext>(it.startMs,null,false) }
        return (observations+invalidations).groupBy { it.atMs }.toSortedMap().map { (at,values) ->
            if (values.any { !it.available }) Observation<RoadContext>(at,null,false) else values.first()
        }
    }
}
