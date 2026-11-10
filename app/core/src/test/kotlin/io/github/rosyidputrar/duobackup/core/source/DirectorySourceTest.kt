package io.github.rosyidputrar.duobackup.core.source

import io.github.rosyidputrar.duobackup.core.util.ExcludeMatcher
import io.github.rosyidputrar.duobackup.core.util.InvalidRelPathException
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DirectorySourceTest {
    @TempDir
    lateinit var root: File

    private lateinit var source: DirectorySource

    private fun file(path: String, content: String = "x"): File = File(root, path).apply {
        parentFile.mkdirs()
        writeText(content)
    }

    private fun walk(
        roots: List<String> = listOf(""),
        excludes: List<String> = emptyList()
    ): Pair<List<String>, WalkSummary> {
        val seen = ArrayList<String>()
        val summary = source.walk(roots, ExcludeMatcher(excludes)) { seen += it.path }
        return seen.sorted() to summary
    }

    @BeforeEach
    fun setUp() {
        source = DirectorySource("primary", root)
        file("DCIM/Camera/a.jpg", "12345")
        file("DCIM/Camera/b.jpg")
        file("DCIM/.thumbnails/t.jpg")
        file("Documents/note.txt")
        file("Android/data/com.example/secret.bin")
        file("top.txt")
        File(root, "Empty").mkdirs()
    }

    @Test
    fun `walks every regular file with size and mtime`() {
        val entries = ArrayList<SourceEntry>()
        val summary = source.walk(listOf(""), ExcludeMatcher(emptyList())) { entries += it }
        assertEquals(WalkSummary(files = 6, unreadable = 0), summary)
        val a = entries.single { it.path == "DCIM/Camera/a.jpg" }
        assertEquals("primary", a.volume)
        assertEquals(5L, a.size)
        assertEquals(File(root, "DCIM/Camera/a.jpg").lastModified(), a.mtime)
    }

    @Test
    fun `default excludes prune app data and thumbnails`() {
        val (paths, summary) = walk(excludes = ExcludeMatcher.DEFAULT_GLOBS)
        assertEquals(listOf("DCIM/Camera/a.jpg", "DCIM/Camera/b.jpg", "Documents/note.txt", "top.txt"), paths)
        assertEquals(4L, summary.files)
    }

    @Test
    fun `only the chosen roots are walked and nested roots are not walked twice`() {
        assertEquals(
            listOf("DCIM/.thumbnails/t.jpg", "DCIM/Camera/a.jpg", "DCIM/Camera/b.jpg"),
            walk(listOf("DCIM")).first
        )
        assertEquals(
            listOf("DCIM/.thumbnails/t.jpg", "DCIM/Camera/a.jpg", "DCIM/Camera/b.jpg", "Documents/note.txt"),
            walk(listOf("DCIM", "DCIM/Camera", "Documents", "DCIM")).first
        )
        assertEquals(6, walk(listOf("DCIM", "")).first.size)
    }

    @Test
    fun `a missing root is skipped and a root that is a file counts as unreadable`() {
        assertEquals(emptyList<String>() to WalkSummary(0, 0), walk(listOf("Nope")))
        assertEquals(emptyList<String>() to WalkSummary(0, 1), walk(listOf("top.txt")))
    }

    @Test
    fun `symbolic links are not followed`() {
        Files.createSymbolicLink(File(root, "link-to-dcim").toPath(), File(root, "DCIM").toPath())
        Files.createSymbolicLink(File(root, "link-to-file").toPath(), File(root, "top.txt").toPath())
        val (paths, _) = walk()
        assertEquals(6, paths.size)
        assertEquals(emptyList(), paths.filter { it.startsWith("link") })
        assertEquals(emptyList(), source.listFolders("").filter { it.startsWith("link") })
    }

    @Test
    fun `unreadable directories are counted and the walk goes on`() {
        val locked = File(root, "Locked").apply { mkdirs() }
        file("Locked/hidden.txt")
        locked.setReadable(false)
        try {
            val (paths, summary) = walk()
            // A process running as root can read the directory anyway.
            if (locked.canRead()) return
            assertEquals(6, paths.size)
            assertEquals(1L, summary.unreadable)
        } finally {
            locked.setReadable(true)
        }
    }

    @Test
    fun `lists sub folders sorted`() {
        assertEquals(listOf("Android", "DCIM", "Documents", "Empty"), source.listFolders(""))
        assertEquals(listOf(".thumbnails", "Camera"), source.listFolders("DCIM"))
        assertEquals(emptyList(), source.listFolders("Empty"))
        assertEquals(emptyList(), source.listFolders("Missing"))
    }

    @Test
    fun `stat and open read a single file`() {
        assertEquals(5L, source.stat("DCIM/Camera/a.jpg")?.size)
        assertNull(source.stat("DCIM/Camera"))
        assertNull(source.stat("missing.txt"))
        assertEquals("12345", source.open("DCIM/Camera/a.jpg").use { it.readBytes().decodeToString() })
    }

    @Test
    fun `paths that escape the volume are refused`() {
        assertFailsWith<InvalidRelPathException> { source.stat("../outside") }
        assertFailsWith<InvalidRelPathException> { source.open("/etc/passwd") }
        assertFailsWith<InvalidRelPathException> { source.listFolders("a/../..") }
    }
}
