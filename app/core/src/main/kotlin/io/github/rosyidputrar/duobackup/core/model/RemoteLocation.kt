package io.github.rosyidputrar.duobackup.core.model

/** Where one uploaded object (a part, an index or a key file) lives on a destination. */
sealed class RemoteLocation {
    abstract val destination: DestinationId

    data class Telegram(
        val chatId: Long,
        val messageId: Long,
        val remoteFileId: String,
        val remoteUniqueId: String? = null
    ) : RemoteLocation() {
        override val destination: DestinationId get() = DestinationId.TELEGRAM
    }

    data class Drive(val fileId: String) : RemoteLocation() {
        override val destination: DestinationId get() = DestinationId.DRIVE
    }
}
