package io.github.rosyidputrar.duobackup.core.util

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class PathUtilsTest {
    @Test
    fun `valid relative paths are returned unchanged`() {
        for (path in listOf(
            "a",
            "DCIM/Camera/a.jpg",
            "Music/My Song (1).mp3",
            "a/.hidden",
            "写真/😀.jpg",
            "a\\b.txt",
            "..."
        )) {
            assertEquals(path, RelPath.normalize(path))
        }
    }

    @Test
    fun `unsafe paths are rejected`() {
        val absoluteOrEmpty = listOf("", "/", "/abs/path", "a//b", "a/", "a\u0000b")
        val dotSegments = listOf("a/../b", "..", "../a", "a/..", ".", "a/./b")
        for (path in absoluteOrEmpty + dotSegments) {
            assertFailsWith<InvalidRelPathException>(path) { RelPath.normalize(path) }
        }
    }

    @Test
    fun `file name is the last segment`() {
        assertEquals("a.jpg", RelPath.fileName("DCIM/Camera/a.jpg"))
        assertEquals("a.jpg", RelPath.fileName("a.jpg"))
    }

    @Test
    fun `default excludes skip app data caches thumbnails trash and temp files`() {
        val matcher = ExcludeMatcher(ExcludeMatcher.DEFAULT_GLOBS)
        val excluded = listOf(
            "Android/data",
            "Android/data/com.example/files/a.bin",
            "Android/obb/com.example/main.obb",
            ".thumbnails/1.jpg",
            "DCIM/.thumbnails/1.jpg",
            "DCIM/.thumbnails",
            "Pictures/app/.cache/x",
            "Pictures/app/cache/deep/x",
            "cache/x",
            ".trash/a",
            "Pictures/.trashed-123/a.jpg",
            "Pictures/.Trash-1000/files/a",
            "Download/file.tmp",
            "file.tmp",
            "Download/movie.mkv.part",
            "Download/.movie.mkv.duobackup-part",
            "DCIM/.nomedia",
            ".nomedia"
        )
        val included = listOf(
            "Android/media/com.whatsapp/WhatsApp/Media/a.jpg",
            "Android",
            "DCIM/Camera/a.jpg",
            "DCIM/thumbnails/a.jpg",
            "Documents/cache.txt",
            "Documents/mycache/a",
            "Download/file.tmp.zip",
            "Download/partial.txt",
            "Music/trash/a.mp3",
            "DCIM/.nomedia.txt",
            "Backups/Android/data/a"
        )
        excluded.forEach { assertTrue(matcher.matches(it), it) }
        included.forEach { assertFalse(matcher.matches(it), it) }
    }

    @Test
    fun `glob operators stay inside a segment unless doubled`() {
        assertTrue(ExcludeMatcher(listOf("*.jpg")).matches("a.jpg"))
        assertFalse(ExcludeMatcher(listOf("*.jpg")).matches("dir/a.jpg"))
        assertTrue(ExcludeMatcher(listOf("a?c")).matches("abc"))
        assertFalse(ExcludeMatcher(listOf("a?c")).matches("a/c"))
        assertFalse(ExcludeMatcher(listOf("a?c")).matches("ac"))
        assertTrue(ExcludeMatcher(listOf("a/**/z")).matches("a/z"))
        assertTrue(ExcludeMatcher(listOf("a/**/z")).matches("a/b/c/z"))
        assertFalse(ExcludeMatcher(listOf("a/**/z")).matches("ab/z"))
        assertTrue(ExcludeMatcher(listOf("a**z")).matches("a/b/z"))
        assertTrue(ExcludeMatcher(listOf("**")).matches("anything/at/all"))
        assertTrue(ExcludeMatcher(listOf("file (1).[x]+$")).matches("file (1).[x]+$"))
        assertFalse(ExcludeMatcher(listOf("a.b")).matches("aXb"))
        assertFalse(ExcludeMatcher(emptyList()).matches("a"))
        assertFalse(ExcludeMatcher(listOf("Camera")).matches("camera"))
    }

    @Test
    fun `mime type comes from the extension`() {
        assertEquals("image/jpeg", MimeGuess.fromName("a.jpg"))
        assertEquals("image/jpeg", MimeGuess.fromName("DCIM/Camera/IMG.JPEG"))
        assertEquals("video/mp4", MimeGuess.fromName("clip.final.mp4"))
        assertEquals("application/pdf", MimeGuess.fromName("doc.PDF"))
        assertNull(MimeGuess.fromName("README"))
        assertNull(MimeGuess.fromName(".nomedia"))
        assertNull(MimeGuess.fromName("trailing."))
        assertNull(MimeGuess.fromName("file.unknownext"))
        assertNull(MimeGuess.fromName("dir.d/file"))
    }

    @Test
    fun `hex round trips and rejects bad input`() {
        assertEquals("00ff10ab", Hex.encode(byteArrayOf(0, -1, 16, -85)))
        assertContentEquals(byteArrayOf(0, -1, 16, -85), Hex.decode("00FF10ab"))
        assertFailsWith<IllegalArgumentException> { Hex.decode("abc") }
        assertFailsWith<IllegalArgumentException> { Hex.decode("zz") }
    }

    @Test
    fun `system clock and random are usable defaults`() {
        assertTrue(SystemClock.now() > 1_700_000_000_000)
        assertEquals(24, SystemSecureRandom.nextBytes(24).size)
        assertFalse(SystemSecureRandom.nextBytes(16).contentEquals(SystemSecureRandom.nextBytes(16)))
    }
}
