package io.github.rosyidputrar.duobackup.core

import io.github.rosyidputrar.duobackup.core.util.Hex
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test

class HasherTest {
    private fun sha256(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): String =
        Hex.encode(MessageDigest.getInstance("SHA-256").apply { update(bytes, from, to - from) }.digest())

    @Test
    fun `empty input has the empty hash and no parts`() {
        val result = Hasher.hash(ByteArrayInputStream(ByteArray(0)), 1024)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", result.sha256)
        assertEquals(0L, result.size)
        assertEquals(emptyList(), result.partHashes)
    }

    @Test
    fun `abc matches the RFC 6234 vector`() {
        val result = Hasher.hash(ByteArrayInputStream("abc".toByteArray()), 1024)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", result.sha256)
        assertEquals(3L, result.size)
        assertEquals(listOf(result.sha256), result.partHashes)
    }

    @Test
    fun `part hashes equal the hashes of the sliced ranges`() {
        val data = Random(7).nextBytes(3 * Limits.FRAME + 12_345)
        for (partSize in listOf(1000L, Limits.FRAME.toLong(), Limits.FRAME + 1L, 2_500_000L, 10L * Limits.FRAME)) {
            val result = Hasher.hash(ByteArrayInputStream(data), partSize)
            val spans = PartSizing.layout(data.size.toLong(), partSize)
            assertEquals(sha256(data), result.sha256)
            assertEquals(data.size.toLong(), result.size)
            assertEquals(
                spans.map { sha256(data, it.offset.toInt(), (it.offset + it.length).toInt()) },
                result.partHashes,
                "partSize=$partSize"
            )
            assertEquals(data.size.toLong(), spans.sumOf { it.length })
        }
    }

    @Test
    fun `input exactly on a part boundary has no empty trailing part`() {
        val data = Random(1).nextBytes(4000)
        assertEquals(4, Hasher.hash(ByteArrayInputStream(data), 1000).partHashes.size)
    }

    @Test
    fun `short reads from the stream do not change the result`() {
        val data = Random(3).nextBytes(50_000)
        val trickle = object : InputStream() {
            private val inner = ByteArrayInputStream(data)

            override fun read(): Int = inner.read()

            override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, minOf(len, 777))
        }
        assertEquals(Hasher.hash(ByteArrayInputStream(data), 4096), Hasher.hash(trickle, 4096))
    }

    @Test
    fun `cancellable variant matches the blocking one`() = runTest {
        val data = Random(5).nextBytes(10_000)
        assertEquals(
            Hasher.hash(ByteArrayInputStream(data), 3000),
            Hasher.hashCancellable(ByteArrayInputStream(data), 3000)
        )
    }

    @Test
    fun `cancellable variant stops when the caller is cancelled`() = runTest {
        val job = Job()
        val cancelling = object : InputStream() {
            override fun read(): Int = 0

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                job.cancel()
                return len
            }
        }
        assertFailsWith<CancellationException> {
            withContext(job) { Hasher.hashCancellable(cancelling, 1024) }
        }
    }

    @Test
    fun `part size must be positive`() {
        assertFailsWith<IllegalArgumentException> { Hasher.hash(ByteArrayInputStream(ByteArray(1)), 0) }
    }
}
