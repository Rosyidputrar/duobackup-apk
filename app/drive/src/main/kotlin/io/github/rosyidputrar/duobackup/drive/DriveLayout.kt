package io.github.rosyidputrar.duobackup.drive

import io.github.rosyidputrar.duobackup.core.format.DriveProps
import io.github.rosyidputrar.duobackup.core.format.RemoteNames
import kotlinx.serialization.Serializable

/** Ids of DuoBackup's folders on Drive. */
@Serializable
data class DriveFolders(val root: String, val data: String, val index: String, val key: String)

/** Remembers the folder ids between sessions. The app keeps them in settings. */
interface DriveFolderCache {
    suspend fun load(): DriveFolders?

    suspend fun save(folders: DriveFolders?)
}

/** @property warnings things the user should know, such as a second DuoBackup folder */
data class ResolvedLayout(val folders: DriveFolders, val warnings: List<String> = emptyList())

/**
 * Finds or creates `DuoBackup/` with its `data/`, `index/` and `key/` folders. Folders are
 * always found through their `appProperties`, never by name, so renaming them changes nothing.
 */
class DriveLayout(
    private val api: DriveApi,
    private val cache: DriveFolderCache,
    private val installId: suspend () -> String
) {
    /** Returns the cached layout, or looks it up on Drive and creates what is missing. */
    suspend fun resolve(): ResolvedLayout {
        cache.load()?.let { return ResolvedLayout(it) }
        val warnings = ArrayList<String>()
        val roots = api.listAllFiles(folderQuery(DriveProps.KIND_ROOT, parent = null))
        val root = when (roots.size) {
            0 -> api.createFolder(RemoteNames.STORAGE_TITLE, null, DriveProps.root(installId()))
            1 -> roots.single()
            else -> {
                warnings += WARNING_MULTIPLE_ROOTS
                newestRoot(roots)
            }
        }
        val folders = DriveFolders(
            root = root.id,
            data = child(root.id, DriveProps.KIND_DATA_DIR, "data", DriveProps.dataDir()),
            index = child(root.id, DriveProps.KIND_INDEX_DIR, "index", DriveProps.indexDir()),
            key = child(root.id, DriveProps.KIND_KEY_DIR, "key", DriveProps.keyDir())
        )
        cache.save(folders)
        return ResolvedLayout(folders, warnings)
    }

    /** Forgets the cached ids, so the next [resolve] looks on Drive again. */
    suspend fun invalidate() = cache.save(null)

    private suspend fun child(rootId: String, kind: String, name: String, properties: Map<String, String>): String {
        val existing = api.listAllFiles(folderQuery(kind, rootId))
        return (existing.firstOrNull() ?: api.createFolder(name, rootId, properties)).id
    }

    /** The root whose index folder holds the most recent snapshot; without any, the first root. */
    private suspend fun newestRoot(roots: List<DriveFile>): DriveFile {
        var best = roots.first()
        var bestTimestamp = Long.MIN_VALUE
        for (root in roots) {
            val indexDir = api.listAllFiles(folderQuery(DriveProps.KIND_INDEX_DIR, root.id)).firstOrNull() ?: continue
            val newest = api.listAllFiles(indexQuery(indexDir.id))
                .mapNotNull { it.appProperties["ts"]?.toLongOrNull() }
                .maxOrNull() ?: continue
            if (newest > bestTimestamp) {
                bestTimestamp = newest
                best = root
            }
        }
        return best
    }

    companion object {
        const val WARNING_MULTIPLE_ROOTS: String = "MULTIPLE_ROOTS"

        internal fun property(key: String, value: String): String =
            "appProperties has { key='${escape(key)}' and value='${escape(value)}' }"

        internal fun folderQuery(kind: String, parent: String?): String = listOfNotNull(
            parent?.let { "'${escape(it)}' in parents" },
            property(DriveProps.KEY_KIND, kind),
            "mimeType = '$FOLDER_MIME_TYPE'",
            "trashed = false"
        ).joinToString(" and ")

        internal fun indexQuery(indexDir: String): String = "'${escape(
            indexDir
        )}' in parents and ${property(DriveProps.KEY_KIND, DriveProps.KIND_INDEX)} and trashed = false"

        private fun escape(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")
    }
}
