package io.github.rosyidputrar.duobackup.core.transport

import io.github.rosyidputrar.duobackup.core.model.DestinationId
import io.github.rosyidputrar.duobackup.core.model.RemoteLocation
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class RetryPolicyTest {
    @Test
    fun `every transport failure has a decision`() {
        assertEquals(RetryDecision.RetryAfter(31_000), RetryPolicy.classify(TransportException.RateLimited(31_000)))
        assertEquals(RetryDecision.Backoff, RetryPolicy.classify(TransportException.Transient(IOException("reset"))))
        assertEquals(RetryDecision.Backoff, RetryPolicy.classify(TransportException.Transient()))
        assertEquals(RetryDecision.PauseDestination, RetryPolicy.classify(TransportException.QuotaExceeded()))
        assertEquals(RetryDecision.PauseDestination, RetryPolicy.classify(TransportException.AuthRequired()))
        assertEquals(RetryDecision.FailItem, RetryPolicy.classify(TransportException.NotFound()))
        assertEquals(RetryDecision.FailItem, RetryPolicy.classify(TransportException.Permanent("bad request")))
    }

    @Test
    fun `transient failures keep their cause`() {
        val cause = IOException("reset")
        assertSame(cause, TransportException.Transient(cause).cause)
        assertEquals("bad request", TransportException.Permanent("bad request").message)
    }

    @Test
    fun `locations know their destination`() {
        assertEquals(DestinationId.DRIVE, RemoteLocation.Drive("id").destination)
        assertEquals(DestinationId.TELEGRAM, RemoteLocation.Telegram(-100, 5, "file").destination)
    }

    @Test
    fun `index metadata is encrypted exactly when it names a key`() {
        assertFalse(IndexMeta(1).encrypted)
        assertTrue(IndexMeta(1, "0123456789abcdef").encrypted)
        assertFalse(IndexRef(RemoteLocation.Drive("id"), 1).encrypted)
        assertTrue(IndexRef(RemoteLocation.Drive("id"), 1, "0123456789abcdef").encrypted)
    }
}
