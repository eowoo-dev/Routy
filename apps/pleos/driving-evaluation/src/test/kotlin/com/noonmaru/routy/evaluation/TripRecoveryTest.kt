package com.noonmaru.routy.evaluation

import java.io.File
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class TripRecoveryTest {
    @get:Rule val folder=TemporaryFolder()
    private fun append(a:TripArchive,at:Long)=a.append(at,"speed",mapOf("atMs" to at,"value" to 10.0,"available" to true))
    @Test fun capturedArchiveCanBeReevaluatedDeterministicallyAfterRestart() {
        val a=TripArchive(File(folder.root,"captured"),0,1000,windowMs=1000)
        append(a,10);append(a,1100);a.finish(2000)
        val original=a.evaluate()
        TripArchive.recover(a.directory).use { reopened ->
            val result=reopened.evaluate()
            assertEquals(original.summary,result.summary);assertEquals(original.sha256,result.sha256)
        }
    }
    @Test fun incompleteFirstWindowRecoversOnlyCompleteRowsAndLabelsInterruption() {
        val a=TripArchive(File(folder.root,"tail"),100,1000)
        append(a,200);append(a,300);a.close()
        File(a.directory,"raw-0.ndjson").appendText("{broken")
        val recovered=TripArchive.recover(a.directory)
        assertEquals(2,recovered.session(0).speeds.size)
        val result=recovered.evaluate()
        assertEquals(301L,result.report.endMs)
        assertEquals("INTERRUPTED",result.summary["recovery"].jsonObject()["status"])
        assertEquals(7L,(result.summary["recovery"].jsonObject()["discardedTailBytes"] as Number).toLong())
        assertTrue(result.report.feedback.any{it.id=="interrupted-trip"})
        assertTrue(File(a.directory,"interrupted-tail.bin").readText().endsWith("{broken"))
        assertEquals(result.summary,TripArchive.recover(a.directory).evaluate().summary)
    }
    @Test fun damagedSealedWindowCannotBeRecoveredOrPublished() {
        val a=TripArchive(File(folder.root,"corrupt"),0,1000,windowMs=1000)
        append(a,10);append(a,1100);a.close()
        File(a.directory,"raw-0.ndjson").appendText("broken")
        assertFails{TripArchive.recover(a.directory)}
        assertFalse(File(a.directory,"summary.json").exists())
    }
    @Test fun emptyCaptureIsNotPromotedIntoAnInventedDrive() {
        val a=TripArchive(File(folder.root,"empty"),0,1000);a.close()
        assertFails{TripArchive.recover(a.directory)}
    }
    @Test fun crashAfterWindowSealBeforeManifestUpdateKeepsTheCommittedWindow() {
        val a=TripArchive(File(folder.root,"seal"),0,1000,windowMs=1000)
        val checkpoint=File(a.directory,"manifest.json").readText()
        append(a,10);append(a,1100);a.close()
        File(a.directory,"manifest.json").writeText(checkpoint)
        val recovered=TripArchive.recover(a.directory)
        assertEquals(2,recovered.segments)
        assertEquals(1101L,recovered.evaluate().report.endMs)
    }
}
