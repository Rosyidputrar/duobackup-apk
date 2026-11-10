package io.github.rosyidputrar.duobackup.storage

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import io.github.rosyidputrar.duobackup.core.source.DirectorySource
import io.github.rosyidputrar.duobackup.core.source.SourceEntry
import io.github.rosyidputrar.duobackup.core.source.SourceFileSystem
import io.github.rosyidputrar.duobackup.core.source.SourceVolume
import io.github.rosyidputrar.duobackup.core.source.WalkSummary
import io.github.rosyidputrar.duobackup.core.util.ExcludeMatcher
import java.io.InputStream

/**
 * Shared storage of the phone. Only the primary volume is read; removable volumes are listed so
 * the settings screen can show them, but backing them up is not supported yet.
 */
class AndroidSourceFileSystem(private val context: Context) : SourceFileSystem {
    private val primary by lazy { DirectorySource(PRIMARY, Environment.getExternalStorageDirectory()) }

    override fun volumes(): List<SourceVolume> {
        val manager = context.getSystemService(StorageManager::class.java)
        return manager.storageVolumes.map { volume ->
            SourceVolume(
                id = if (volume.isPrimary) PRIMARY else volume.uuid ?: volume.getDescription(context),
                label = volume.getDescription(context),
                removable = volume.isRemovable
            )
        }
    }

    override fun walk(
        volume: String,
        roots: List<String>,
        excludes: ExcludeMatcher,
        visitor: (SourceEntry) -> Unit
    ): WalkSummary = source(volume).walk(roots, excludes, visitor)

    override fun listFolders(volume: String, folder: String): List<String> = source(volume).listFolders(folder)

    override fun stat(volume: String, path: String): SourceEntry? = source(volume).stat(path)

    override fun open(volume: String, path: String): InputStream = source(volume).open(path)

    private fun source(volume: String): DirectorySource {
        require(volume == PRIMARY) { "only the primary volume is supported" }
        return primary
    }

    companion object {
        const val PRIMARY: String = "primary"
    }
}
