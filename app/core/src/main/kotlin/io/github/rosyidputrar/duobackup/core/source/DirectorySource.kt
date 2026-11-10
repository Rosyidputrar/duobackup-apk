package io.github.rosyidputrar.duobackup.core.source

import io.github.rosyidputrar.duobackup.core.util.ExcludeMatcher
import io.github.rosyidputrar.duobackup.core.util.RelPath
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Walks and reads one volume whose files are reachable under `root`. */
class DirectorySource(private val volume: String, root: File) {
    private val root: Path = root.toPath()

    fun walk(roots: List<String>, excludes: ExcludeMatcher, visitor: (SourceEntry) -> Unit): WalkSummary {
        var files = 0L
        var unreadable = 0L
        for (start in startPaths(roots)) {
            if (!Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.exists(start, LinkOption.NOFOLLOW_LINKS)) unreadable++
                continue
            }
            Files.walkFileTree(
                start,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        val rel = relative(dir)
                        return if (rel.isNotEmpty() && excludes.matches(rel)) {
                            FileVisitResult.SKIP_SUBTREE
                        } else {
                            FileVisitResult.CONTINUE
                        }
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        val rel = relative(file)
                        if (attrs.isRegularFile && !excludes.matches(rel)) {
                            visitor(SourceEntry(volume, rel, attrs.size(), attrs.lastModifiedTime().toMillis()))
                            files++
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        unreadable++
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        if (exc != null) unreadable++
                        return FileVisitResult.CONTINUE
                    }
                }
            )
        }
        return WalkSummary(files, unreadable)
    }

    fun listFolders(folder: String): List<String> {
        val dir = resolve(folder)
        val names = dir.toFile().listFiles { child -> child.isDirectory && !Files.isSymbolicLink(child.toPath()) }
        return names.orEmpty().map { it.name }.sorted()
    }

    fun stat(path: String): SourceEntry? {
        val file = resolve(path)
        val attrs = try {
            Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (e: IOException) {
            return null
        }
        if (!attrs.isRegularFile) return null
        return SourceEntry(volume, path, attrs.size(), attrs.lastModifiedTime().toMillis())
    }

    fun open(path: String): InputStream = Files.newInputStream(resolve(path), LinkOption.NOFOLLOW_LINKS)

    /** Roots nested inside another root are dropped so no file is visited twice. */
    private fun startPaths(roots: List<String>): List<Path> {
        if (roots.any { it.isEmpty() }) return listOf(root)
        val distinct = roots.distinct()
        return distinct.filter { candidate -> distinct.none { it != candidate && candidate.startsWith("$it/") } }
            .map(::resolve)
    }

    private fun resolve(path: String): Path = if (path.isEmpty()) root else root.resolve(RelPath.normalize(path))

    private fun relative(path: Path): String = root.relativize(path).joinToString("/") { it.toString() }
}
