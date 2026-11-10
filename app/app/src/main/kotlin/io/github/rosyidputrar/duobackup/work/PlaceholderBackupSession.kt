package io.github.rosyidputrar.duobackup.work

import io.github.rosyidputrar.duobackup.core.catalog.RunResult
import io.github.rosyidputrar.duobackup.core.catalog.RunTrigger
import io.github.rosyidputrar.duobackup.core.engine.BackupSession
import io.github.rosyidputrar.duobackup.core.engine.SessionOutcome

/** Stands in until the backup engine exists: every run finishes at once with nothing to do. */
class PlaceholderBackupSession : BackupSession {
    override suspend fun run(trigger: RunTrigger, budgetMs: Long): SessionOutcome =
        SessionOutcome(result = RunResult.OK, stopReason = STOP_REASON)

    companion object {
        const val STOP_REASON: String = "NO_DESTINATION"
    }
}
