package org.eowoo.routy.pleos.feature.evaluation

import org.eowoo.routy.evaluation.*
import kotlin.test.*

class DiskCaptureQueueTest {
    @Test fun pendingEntryCountAndSizeAreBounded() {
        val queue=DiskCaptureQueue(2)
        assertTrue(queue.offer(1,"speed",mapOf("atMs" to 1)))
        assertTrue(queue.offer(2,"speed",mapOf("atMs" to 2)))
        assertFalse(queue.offer(3,"speed",mapOf("atMs" to 3)))
        assertNotNull(queue.failure)
        assertEquals(1,queue.rows.tryReceive().getOrThrow().atMs)
        assertEquals(2,queue.rows.tryReceive().getOrThrow().atMs)
        assertTrue(queue.rows.tryReceive().isFailure)
    }
    @Test fun overflowFailureSurvivesCloseAndCannotBecomeSuccessDuringStop() {
        val queue=DiskCaptureQueue(1);queue.offer(1,"tick",emptyMap());queue.offer(2,"tick",emptyMap())
        queue.close();queue.rows.tryReceive()
        assertNotNull(queue.failure)
        assertFalse(queue.offer(3,"tick",emptyMap()))
    }
    @Test fun oversizedAndInvalidRowsFailBeforeEnteringTheQueue() {
        for(value in listOf(mapOf("text" to "가".repeat(TripArchive.ROW_BYTES)),mapOf("value" to Double.NaN))) {
            val queue=DiskCaptureQueue()
            assertFalse(queue.offer(1,"readings",value));assertNotNull(queue.failure)
            assertTrue(queue.rows.tryReceive().isFailure)
        }
    }
}
