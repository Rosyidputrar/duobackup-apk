package io.github.rosyidputrar.duobackup.core.util

/**
 * Matches relative paths against exclude globs.
 *
 * - `?` matches one character inside a segment.
 * - `*` matches any run of characters inside a segment.
 * - A double star matches across segments. As a leading segment it also matches at the root,
 *   and as a trailing segment it also matches the directory itself, so a walk can prune it.
 */
class ExcludeMatcher(globs: List<String>) {
    private val patterns: List<Regex> = globs.map(::compile)

    fun matches(relPath: String): Boolean = patterns.any { it.matches(relPath) }

    private fun compile(glob: String): Regex {
        val out = StringBuilder()
        var i = 0
        while (i < glob.length) {
            when {
                glob.startsWith("**/", i) && (i == 0 || glob[i - 1] == '/') -> {
                    out.append("(?:.*/)?")
                    i += 3
                }
                glob.startsWith("/**", i) && i + 3 == glob.length -> {
                    out.append("(?:/.*)?")
                    i += 3
                }
                glob.startsWith("**", i) -> {
                    out.append(".*")
                    i += 2
                }
                glob[i] == '*' -> {
                    out.append("[^/]*")
                    i++
                }
                glob[i] == '?' -> {
                    out.append("[^/]")
                    i++
                }
                else -> {
                    out.append(Regex.escape(glob[i].toString()))
                    i++
                }
            }
        }
        return Regex(out.toString(), RegexOption.DOT_MATCHES_ALL)
    }

    companion object {
        /** Excludes applied to a fresh install. The user can edit the list. */
        val DEFAULT_GLOBS: List<String> = listOf(
            "Android/data/**",
            "Android/obb/**",
            "**/.thumbnails/**",
            "**/.cache/**",
            "**/cache/**",
            "**/.trash*/**",
            "**/.Trash*/**",
            "**/*.tmp",
            "**/*.part",
            "**/*.duobackup-part",
            "**/.nomedia",
            "DuoBackup Restore/**"
        )
    }
}
