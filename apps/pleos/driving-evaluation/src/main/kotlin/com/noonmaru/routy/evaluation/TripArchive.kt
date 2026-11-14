package com.noonmaru.routy.evaluation

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** One writer, bounded records/windows; no collection grows with the duration of a trip. */
class TripArchive(val directory:File,val startMs:Long,val startedEpochMs:Long,
    private val windowMs:Long=60_000,private val windowBytes:Int=524_288) : AutoCloseable {
    companion object {
        const val ROW_BYTES=65_536
        const val PART_BYTES=196_608
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
    init {
        require(startMs>=0 && startedEpochMs>=0 && windowMs>0 && windowBytes>=ROW_BYTES)
        require(!directory.exists() || directory.list().isNullOrEmpty()) { "Archive already exists" }
        directory.mkdirs();checkpoint("CAPTURING",startMs)
    }
    private fun checkpoint(status:String,end:Long)=atomic(File(directory,"manifest.json"),mapOf(
        "schemaVersion" to 2,"storage" to "SEGMENTED","status" to status,"startMs" to startMs,
        "endMs" to end,"startedEpochMs" to startedEpochMs,"segments" to segments))
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
        val aggregate=WindowReportSummary()
        val observedSignals=mutableSetOf<String>()
        var previous:DrivingSession?=null
        val target=File(directory,"reports.ndjson");val temp=File(directory,"reports.ndjson.tmp")
        FileOutputStream(temp).use { stream ->
            for(index in 0 until segments) {
                val core=session(index,availability,network)
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
                aggregate.add(metrics.copy(events=report.events.filter{it.endMs>core.startMs}.map{it.copy(startMs=maxOf(core.startMs,it.startMs))},
                    contextFindings=report.contextFindings.filter{it.endMs>core.startMs}.map{it.copy(startMs=maxOf(core.startMs,it.startMs))},
                    feedback=report.feedback.filter{it.endMs>core.startMs}.map{it.copy(startMs=maxOf(core.startMs,it.startMs))}))
                previous=core
                val row=mapOf("index" to index,"coreStartMs" to core.startMs,"coreEndMs" to core.endMs,"report" to TraceReplay.summary(report))
                stream.write((JsonDocuments.compact(row)+"\n").toByteArray())
                // A bounded replay document preserves every window's original context.
                atomic(File(directory,"evaluation-$index.json"),TraceReplay.recording(input,report))
            }
            stream.fd.sync()
        }
        Files.move(temp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
        val report=aggregate.report().let{it.copy(telemetry=it.telemetry.copy(channels=observedSignals.size))}
        val summary=TraceReplay.summary(report)+mapOf("segmentation" to mapOf("windows" to segments,
            "previewLimit" to WindowReportSummary.PREVIEW,"details" to "ARCHIVE",
            "boundaryPolicy" to "Disjoint metric windows with up to 10s/one-window event lookback; longer boundary maneuvers may be unassessed"))
        atomic(File(directory,"summary.json"),summary)
        return Evaluation(report,summary,target,((target.length()+PART_BYTES-1)/PART_BYTES).toInt(),target.length(),sha256(target))
    }
}
