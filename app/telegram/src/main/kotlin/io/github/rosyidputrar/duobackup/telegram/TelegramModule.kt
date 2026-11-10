package io.github.rosyidputrar.duobackup.telegram

import io.github.rosyidputrar.duobackup.core.model.DestinationId

/** Module marker for the Telegram transport. The transport itself arrives in M6. */
object TelegramModule {
    val destination: DestinationId = DestinationId.TELEGRAM
}
