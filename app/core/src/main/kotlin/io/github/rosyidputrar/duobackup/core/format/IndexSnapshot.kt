package io.github.rosyidputrar.duobackup.core.format

import io.github.rosyidputrar.duobackup.core.model.DestinationId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The index snapshot written to every destination after a session (plan §4.5). */
@Serializable
data class IndexSnapshot(
    val format: String = FORMAT,
    val v: Int = VERSION,
    val generatedAt: Long,
    val device: String,
    val install: String,
    val encryption: Encryption = Encryption(),
    val session: Session? = null,
    val quota: Map<DestinationId, Quota> = emptyMap(),
    val contents: List<Content> = emptyList(),
    val files: List<FileEntry> = emptyList()
) {
    @Serializable
    data class Encryption(val enabled: Boolean = false, val keyId: String? = null)

    @Serializable
    data class Session(
        val runId: Long,
        val result: String,
        val pendingParts: Int,
        val bytesUploaded: Map<DestinationId, Long> = emptyMap()
    )

    /** `limit` is null for accounts without a fixed quota. */
    @Serializable
    data class Quota(val limit: Long? = null, val usage: Long)

    @Serializable
    data class Content(
        @SerialName("h") val sha256: String,
        @SerialName("o") val oid: String,
        @SerialName("s") val size: Long,
        @SerialName("ps") val partSize: Long,
        @SerialName("e") val encrypted: Int,
        val parts: List<Part> = emptyList()
    )

    /** A part entry carries only the destinations where the part is located. */
    @Serializable
    data class Part(
        @SerialName("i") val index: Int,
        @SerialName("ph") val sha256: String,
        @SerialName("tg") val telegram: TelegramRef? = null,
        @SerialName("gd") val driveFileId: String? = null
    )

    @Serializable
    data class TelegramRef(
        @SerialName("c") val chatId: Long,
        @SerialName("m") val messageId: Long,
        @SerialName("f") val remoteFileId: String
    )

    @Serializable
    data class FileEntry(
        @SerialName("vol") val volume: String,
        @SerialName("p") val path: String,
        @SerialName("h") val sha256: String,
        @SerialName("s") val size: Long,
        @SerialName("mt") val mtime: Long,
        @SerialName("m") val mime: String? = null
    )

    companion object {
        const val FORMAT: String = "duobackup-index"
        const val VERSION: Int = 1

        /**
         * Combines the snapshots read from two destinations. The one with the newest
         * `generatedAt` is the base (`a` on a tie). Contents are unioned by hash, and the part
         * locations known to either side add up.
         */
        fun merge(a: IndexSnapshot, b: IndexSnapshot): IndexSnapshot {
            val (base, other) = if (b.generatedAt > a.generatedAt) b to a else a to b
            val otherByHash = other.contents.associateBy { it.sha256 }
            val baseHashes = base.contents.mapTo(HashSet()) { it.sha256 }
            val merged = base.contents.map { content ->
                otherByHash[content.sha256]?.let { mergeContent(content, it) } ?: content
            }
            return base.copy(contents = merged + other.contents.filter { it.sha256 !in baseHashes })
        }

        private fun mergeContent(base: Content, other: Content): Content {
            val otherParts = other.parts.associateBy { it.index }
            val baseIndexes = base.parts.mapTo(HashSet()) { it.index }
            val merged = base.parts.map { part ->
                val extra = otherParts[part.index]
                part.copy(
                    telegram = part.telegram ?: extra?.telegram,
                    driveFileId = part.driveFileId ?: extra?.driveFileId
                )
            }
            val added = other.parts.filter { it.index !in baseIndexes }
            return base.copy(parts = (merged + added).sortedBy { it.index })
        }
    }
}
