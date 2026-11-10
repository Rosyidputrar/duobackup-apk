package io.github.rosyidputrar.duobackup.drive

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val DriveJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

const val FOLDER_MIME_TYPE: String = "application/vnd.google-apps.folder"

/** The subset of a Drive file resource DuoBackup reads. Drive sends 64-bit numbers as strings. */
@Serializable
data class DriveFile(
    val id: String,
    val name: String? = null,
    val size: Long? = null,
    val md5Checksum: String? = null,
    val trashed: Boolean = false,
    val appProperties: Map<String, String> = emptyMap(),
    val parents: List<String> = emptyList(),
    val createdTime: String? = null
)

/** What is sent when a file or folder is created or changed. Null fields are left out. */
@Serializable
data class DriveFileMetadata(
    val name: String? = null,
    val mimeType: String? = null,
    val parents: List<String>? = null,
    val appProperties: Map<String, String>? = null
)

@Serializable
data class DriveFileList(val files: List<DriveFile> = emptyList(), val nextPageToken: String? = null)

@Serializable
data class DriveAbout(val user: User? = null, val storageQuota: StorageQuota = StorageQuota()) {
    @Serializable
    data class User(val emailAddress: String? = null)

    /** `limit` is absent for accounts with unlimited storage. */
    @Serializable
    data class StorageQuota(val limit: Long? = null, val usage: Long = 0, val usageInDrive: Long = 0) {
        val freeBytes: Long? get() = limit?.let { (it - usage).coerceAtLeast(0) }
    }
}
