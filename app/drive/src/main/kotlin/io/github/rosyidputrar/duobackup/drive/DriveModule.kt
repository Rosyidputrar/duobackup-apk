package io.github.rosyidputrar.duobackup.drive

import io.github.rosyidputrar.duobackup.core.model.DestinationId

/** Module marker for the Google Drive transport. The transport itself arrives in M3. */
object DriveModule {
    val destination: DestinationId = DestinationId.DRIVE
}
