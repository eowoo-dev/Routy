package org.eowoo.routy.evaluation

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** One writer, bounded records/windows; no collection grows with the duration of a trip. */
class TripArchive(val directory:File,val startMs:Long,val startedEpochMs:Long,
    private val windowMs:Long=60_000,private val windowBytes:Int=524_288,private val reopening:Boolean=false) : AutoCloseable {
    companion object {
        const val ROW_BYTES=65_536
        const val PART_BYTES=196_608
        /** Recovery never resumes acquisition or infers that the driver parked. */
        fun recover(directory:File):TripArchive {
            val file=File(directory,"manifest.json")
            require(file.length() in 1..65_536)
            val m=JsonDocuments.read(file.readText())
            require(m.long("schemaVersion")==2L && m.string("storage")=="SEGMENTED")
            return TripArchive(directory,m.long("startMs"),m.long("startedEpochMs"),
                (m["windowMs"] as? Number)?.toLong() ?: 60_000,(m["windowBytes"] as? Number)?.toInt() ?: 524_288,true)
        }
        fun atomic(file:File,value:Any) {
            file.parentFile.mkdirs()
            val temp=File(file.parentFile,file.name+".tmp")
            FileOutputStream(temp).use { it.write(JsonDocuments.compact(value).toByteArray());it.fd.sync() }
            Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
        }
        fun sha256(file:File):String {
            val hash=MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer=ByteArray(32_768);while(true){val n=input.read(buffer);if(n<0)break;hash.update(buffer,0,n)} }
            return hash.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
        }
    }
    var segments=0;private set
    private var segmentStart=startMs
    private var bytes=0
    private var output:FileOutputStream?=null
    private var speedGap:Long?=null
    private var steeringGap:Long?=null
    private var closed=false
    private var completed=false
    private var lastAt=startMs
    private var lastSync=startMs
    private var recovery:JsonObject?=null
    init {
        require(startMs>=0 && startedEpochMs>=0 && windowMs>0 && windowBytes>=ROW_BYTES)
        if(reopening)restore() else {
            require(!directory.exists() || directory.list().isNullOrEmpty()) { "Archive already exists" }
            directory.mkdirs();checkpoint("CAPTURING",startMs)
        }
    }
    private fun checkpoint(status:String,end:Long)=atomic(File(directory,"manifest.json"),mapOf(
        "schemaVersion" to 2,"storage" to "SEGMENTED","status" to status,"startMs" to startMs,
        "endMs" to end,"startedEpochMs" to startedEpochMs,"segments" to segments,
        "windowMs" to windowMs,"windowBytes" to windowBytes,"speedGap" to speedGap,"steeringGap" to steeringGap,"recovery" to recovery))
    private fun restore() {
        val m=JsonDocuments.read(File(directory,"manifest.json").readText())
        require(m.string("status") in setOf("CAPTURING","CAPTURED","INTERRUPTED"))
        recovery=m["recovery"]?.jsonObject()?.let { mapOf("status" to it.string("status"),"reason" to it.string("reason"),"discardedTailBytes" to it.long("discardedTailBytes")) }
        speedGap=(m["speedGap"] as? Number)?.toLong();steeringGap=(m["steeringGap"] as? Number)?.toLong()
        val declared=m.long("segments").toInt();require(declared>=0)
        while(File(directory,"window-$segments.json").exists()) {
            val infoFile=File(directory,"window-$segments.json");require(infoFile.length() in 1..65_536)
            val info=JsonDocuments.read(infoFile.readText());val raw=File(directory,"raw-$segments.ndjson")
            require(info.long("startMs")==segmentStart && info.long("endMs")>segmentStart)
            require(raw.length()<=windowBytes+ROW_BYTES*4 && sha256(raw)==info.string("sha256")){"Recovery raw window damaged"}
            segmentStart=info.long("endMs");segments++
        }
        require(segments>=declared){"Recovery window missing"}
        if(m.string("status")=="CAPTURED" || m.string("status")=="INTERRUPTED") {
            require(segments==declared && segmentStart==m.long("endMs"))
        } else {
            val tail=File(directory,"raw-$segments.ndjson")
            var lost=0L
            if(tail.exists() && tail.length()>0) {
                require(tail.length()<=windowBytes+ROW_BYTES*4)
                val original=tail.readBytes();var offset=0;var validEnd=0;var end=segmentStart
                while(offset<original.size) {
                    val newline=(offset until original.size).firstOrNull{original[it]==10.toByte()} ?: break
                    if(newline-offset>ROW_BYTES)break
                    val row=runCatching{JsonDocuments.read(original.copyOfRange(offset,newline).toString(Charsets.UTF_8))}.getOrNull() ?: break
                    if(row["kind"] !is String || row["value"] !is Map<*,*>)break
                    val value=row["value"].jsonObject()
                    for(key in listOf("atMs","sessionAtMs","endMs")) (value[key] as? Number)?.toLong()?.let{end=maxOf(end,it)}
                    when(row["kind"]) {
                        "speedGapStart"->speedGap=value.long("atMs");"speedGapEnd"->speedGap=null
                        "steeringGapStart"->steeringGap=value.long("atMs");"steeringGapEnd"->steeringGap=null
                    }
                    validEnd=newline+1;offset=validEnd
                }
                lost=(original.size-validEnd).toLong()
                // Preserve the original interrupted tail as evidence before normalizing it.
                val evidence=File(directory,"interrupted-tail.bin")
                if(!evidence.exists())FileOutputStream(evidence).use{it.write(original);it.fd.sync()}
                FileOutputStream(tail).use{it.write(original,0,validEnd);it.fd.sync()}
                bytes=validEnd
                if(validEnd>0) {output=FileOutputStream(tail,true);seal(Math.addExact(end,1))}
            }
            require(segments>0){"No recoverable observations"}
            recovery=mapOf("status" to "INTERRUPTED","reason" to "APPLICATION_INTERRUPTED","discardedTailBytes" to lost)
            checkpoint("INTERRUPTED",segmentStart)
        }
        lastAt=segmentStart;closed=true;completed=true
    }
    private fun write(kind:String,value:JsonObject) {
        val row=(JsonDocuments.compact(mapOf("kind" to kind,"value" to value))+"\n").toByteArray()
        require(row.size<=ROW_BYTES) { "Sensor row exceeds bounded record size; recording was not completed" }
        val stream=output ?: FileOutputStream(File(directory,"raw-$segments.ndjson")).also{output=it}
        stream.write(row);bytes+=row.size
    }
    fun append(atMs:Long,kind:String,value:JsonObject) {
        check(!closed)
        val at=maxOf(lastAt,atMs);lastAt=at
        if(bytes>=windowBytes) {
            check(at>segmentStart){"Sensor clock stalled while raw window was full"}
            seal(at)
        }else if(at-segmentStart>=windowMs)seal(at)
        when(kind) {
            "speedGapStart" -> if(speedGap==null)speedGap=value.long("atMs")
            "steeringGapStart" -> if(steeringGap==null)steeringGap=value.long("atMs")
            "speedGapEnd" -> {range("speedGaps",speedGap,value.long("atMs"));speedGap=null}
            "steeringGapEnd" -> {range("steeringGaps",steeringGap,value.long("atMs"));steeringGap=null}
        }
        write(kind,value)
        if(at-lastSync>=1000){output?.fd?.sync();lastSync=at}
    }
    private fun range(kind:String,start:Long?,end:Long) {
        if(start!=null && end>maxOf(start,segmentStart))write(kind,mapOf("startMs" to maxOf(start,segmentStart),"endMs" to end))
    }
    private fun seal(end:Long) {
        if(end<=segmentStart)return
        range("speedGaps",speedGap,end);range("steeringGaps",steeringGap,end)
        val file=File(directory,"raw-$segments.ndjson")
        if(output==null)output=FileOutputStream(file)
        output!!.fd.sync();output!!.close();output=null
        atomic(File(directory,"window-$segments.json"),mapOf("startMs" to segmentStart,"endMs" to end,"sha256" to sha256(file)))
        segments++;segmentStart=end;bytes=0;checkpoint("CAPTURING",end)
    }
    fun finish(endMs:Long) {
        check(!closed);require(endMs>startMs && endMs>lastAt) { "Close time must follow the last recorded observation" }
        seal(endMs);closed=true;checkpoint("CAPTURED",endMs);completed=true
    }
    override fun close(){output?.close();output=null;closed=true}

    /** Reads at most one size-bounded file, verifying it before decoding. */
    fun session(index:Int,availability:List<SensorAvailability> = emptyList(),network:RoadNetwork?=null):DrivingSession {
        require(index in 0 until segments)
        val info=JsonDocuments.read(File(directory,"window-$index.json").readText())
        val raw=File(directory,"raw-$index.ndjson")
        require(raw.length()<=windowBytes+ROW_BYTES*4 && sha256(raw)==info.string("sha256")) { "Raw window damaged" }
        val start=info.long("startMs");val end=info.long("endMs")
        val groups=mutableMapOf<String,MutableList<JsonObject>>()
        raw.bufferedReader().useLines { lines -> lines.forEach { line ->
            require(line.toByteArray().size<=ROW_BYTES)
            val row=JsonDocuments.read(line);groups.getOrPut(row.string("kind")){mutableListOf()}.add(row["value"].jsonObject())
        } }
        val empty=DrivingSession(start,end,DataOrigin.LIVE,steeringTimeBasis=SteeringTimeBasis.CALLBACK_RECEIPT)
        val header=TraceReplay.recording(empty,DrivingEvaluationEngine().evaluate(empty))
        val document=header+mapOf("engineVersion" to "0.7.0","environmentRuleVersion" to "environment-0.3",
            "speed" to (groups["speed"]?:emptyList()),"steering" to (groups["steering"]?:emptyList()),
            "readings" to (groups["readings"]?:emptyList()),"environment" to (groups["environment"]?:emptyList()),
            "speedGaps" to (groups["speedGaps"]?:emptyList()),"steeringGaps" to (groups["steeringGaps"]?:emptyList()))
        return TraceReplay.session(document).copy(origin=DataOrigin.LIVE,sensorAvailability=availability,roadNetwork=network)
    }
    data class Evaluation(val report:EvaluationReport,val summary:JsonObject,val archive:File,val parts:Int,val bytes:Long,val sha256:String)
    fun evaluate(availability:List<SensorAvailability> = emptyList(),network:RoadNetwork?=null):Evaluation {
        check(completed && segments>0)
        val contextFile=File(directory,"evaluation-context.json")
        if(!contextFile.exists())atomic(contextFile,mapOf("availability" to availability.map{mapOf("signal" to it.signal,"access" to it.access.name,"detail" to it.detail)},
            "network" to network?.let{PracticeCodec.encodeNetwork(it)}))
        require(contextFile.length()<=4_300_000)
        val context=JsonDocuments.read(contextFile.readText())
        val storedAvailability=context.objects("availability").map{SensorAvailability(it.string("signal"),SensorAccess.valueOf(it.string("access")),it.string("detail"))}
        val storedNetwork=context["network"]?.jsonObject()?.let{PracticeCodec.network(it)}
        val aggregate=WindowReportSummary()
        val observedSignals=mutableSetOf<String>()
        var previous:DrivingSession?=null
        val environmentStream=EnvironmentEvaluation.Stream()
        var environmentSeen=false
        val eventFile=File(directory,"trip-events.ndjson.tmp")
        val eventStream=FileOutputStream(eventFile)
        val journal=TripEventJournal { event -> eventStream.write((JsonDocuments.compact(mapOf("kind" to event.kind.name,"startMs" to event.startMs,"endMs" to event.endMs,"evidence" to event.evidence))+"\n").toByteArray()) }
        val environmentCounts=linkedMapOf<String,Long>()
        val target=File(directory,"reports.ndjson");val temp=File(directory,"reports.ndjson.tmp")
        eventStream.use { ledger -> FileOutputStream(temp).use { stream ->
            for(index in 0 until segments) {
                val core=session(index,storedAvailability,storedNetwork)
                observedSignals.addAll(core.telemetry.map{it.signal})
                require(observedSignals.size<=512){"Sensor channel count exceeds the declared read policy"}
                val prior=previous
                val lookbackStart=maxOf(prior?.startMs?:core.startMs,core.startMs-10_000)
                fun ranges(a:List<TimeRange>,b:List<TimeRange>)=(a+b).mapNotNull {
                    val start=maxOf(lookbackStart,it.startMs);val end=minOf(core.endMs,it.endMs)
                    if(end>start)TimeRange(start,end) else null
                }
                val contextual=if(prior==null)core else core.copy(startMs=lookbackStart,
                    speeds=prior.speeds.filter{it.atMs>=lookbackStart && it.atMs<core.startMs}+core.speeds,
                    steeringAngles=prior.steeringAngles.filter{it.atMs>=lookbackStart && it.atMs<core.startMs}+core.steeringAngles,
                    telemetry=prior.telemetry.filter{it.atMs>=lookbackStart && it.atMs<core.startMs}+core.telemetry,
                    environment=prior.environment.filter{it.sessionAtMs>=lookbackStart && it.sessionAtMs<core.startMs}+core.environment,
                    speedGaps=ranges(prior.speedGaps,core.speedGaps),steeringGaps=ranges(prior.steeringGaps,core.steeringGaps))
                val input=PracticeInputs.normalize(contextual)
                val report=DrivingEvaluationEngine().evaluate(input)
                // Metrics use disjoint cores. Context informs events only, never double-counts time/data.
                val metrics=if(prior==null)report else DrivingEvaluationEngine().evaluate(PracticeInputs.normalize(core))
                val (aligned,issues)=EnvironmentIntegration.prepare(input)
                environmentSeen=environmentSeen || core.environment.isNotEmpty()
                val continuous=if(!environmentSeen) null else
                    environmentStream.consume(aligned.filter{it.atMs>=core.startMs},issues=issues.filter{it.endMs>=core.startMs},
                        startMs=core.startMs,endMs=core.endMs,final=index==segments-1)
                continuous?.events?.forEach{environmentCounts[it.kind]=(environmentCounts[it.kind] ?: 0)+1}
                val ownedEvents=report.events.filter{it.endMs>core.startMs}
                val integrated=journal.accept(ownedEvents,core.startMs-10_000,final=index==segments-1)
                val owned=metrics.copy(events=ownedEvents,
                    environment=continuous,
                    assessments=metrics.assessments.map { a -> if(continuous!=null && a.category in setOf(Category.LANE_KEEPING,Category.TRAFFIC_SIGNAL_RESPONSE,Category.FOLLOWING_DISTANCE))
                        EnvironmentIntegration.assess(core,emptyList(),continuous).single{it.category==a.category} else a },
                    contextFindings=report.contextFindings.filter{it.endMs>core.startMs},
                    feedback=report.feedback.filter{it.endMs>core.startMs && !it.id.startsWith("environment-")}+
                        (continuous?.let{EnvironmentIntegration.groupedFeedback(it)} ?: emptyList()))
                aggregate.add(owned)
                // Keep a time-bounded context across any number of byte/time rotations.
                // Only rule-relevant telemetry survives; HVAC and diagnostic payloads stay on disk.
                val cutoff=maxOf(startMs,core.endMs-10_000)
                val contextSignals=setOf("AAOS.GEAR_SELECTION","Vehicle.TurnSignal.getCurrentTurnSignalState",
                    "Vehicle.Brake.getCurrentBrakePedalStatus","Vehicle.DrivingMode.getCruiseControlState",
                    "FusedLocation.location","NaviHelper.onCurrentLocationInfo")
                previous=contextual.copy(startMs=cutoff,
                    speeds=contextual.speeds.filter{it.atMs>=cutoff}.takeLast(8192),
                    steeringAngles=contextual.steeringAngles.filter{it.atMs>=cutoff}.takeLast(8192),
                    telemetry=contextual.telemetry.filter{it.atMs>=cutoff && it.signal in contextSignals}.takeLast(8192).map { reading ->
                        val fields=reading.fields.filterKeys{it in setOf("value.Latitude","value.Longitude")}
                        if(fields.values.any{it.length>256} || (reading.state?.length ?: 0)>128)reading.copy(available=false,fields=emptyMap(),state=null)
                        else reading.copy(fields=fields)
                    },environment=contextual.environment.filter{it.sessionAtMs>=cutoff}.takeLast(1),
                    speedGaps=contextual.speedGaps.mapNotNull{r->if(r.endMs>cutoff)TimeRange(maxOf(cutoff,r.startMs),r.endMs) else null},
                    steeringGaps=contextual.steeringGaps.mapNotNull{r->if(r.endMs>cutoff)TimeRange(maxOf(cutoff,r.startMs),r.endMs) else null})
                val row=mapOf("index" to index,"coreStartMs" to core.startMs,"coreEndMs" to core.endMs,
                    "report" to TraceReplay.summary(owned),"integratedEvents" to integrated.map { mapOf("kind" to it.kind.name,"startMs" to it.startMs,"endMs" to it.endMs,"evidence" to it.evidence) })
                stream.write((JsonDocuments.compact(row)+"\n").toByteArray())
                // A bounded replay document preserves every window's original context.
                atomic(File(directory,"evaluation-$index.json"),TraceReplay.recording(input,report))
            }
            stream.fd.sync();ledger.fd.sync()
        } }
        Files.move(eventFile.toPath(),File(directory,"trip-events.ndjson").toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
        Files.move(temp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
        val report=aggregate.report().let {
            val notice=if(recovery==null)emptyList() else listOf(LearningFeedback("interrupted-trip",it.startMs,it.endMs,ReviewPriority.DATA_CHECK,
                "앱이 중단되기 전의 기록을 복구했어요","마지막으로 확인된 관측까지만 평가했으며 정상 주차 종료를 확인한 기록은 아니에요.",
                "중단 시점과 관측 품질을 확인해주세요.",EvidenceStrength.LIMITED,emptyMap(),setOf("APPLICATION_INTERRUPTED")))
            it.copy(events=journal.overview(),telemetry=it.telemetry.copy(channels=observedSignals.size),feedback=(notice+it.feedback).take(WindowReportSummary.PREVIEW))
        }
        val summary=TraceReplay.summary(report)+mapOf("recovery" to recovery,"continuityVersion" to "trip-1","segmentation" to mapOf("windows" to segments,
            "previewLimit" to WindowReportSummary.PREVIEW,"details" to "ARCHIVE",
            "boundaryPolicy" to "Persistent environment maneuver state; integrated basic events; disjoint metric windows with bounded basic-rule context",
            "eventCounts" to journal.counts.mapKeys{it.key.name},"environmentEventCounts" to environmentCounts,
            "eventDetails" to "integratedEvents in ARCHIVE"))
        atomic(File(directory,"summary.json"),summary)
        return Evaluation(report,summary,target,((target.length()+PART_BYTES-1)/PART_BYTES).toInt(),target.length(),sha256(target))
    }
}
