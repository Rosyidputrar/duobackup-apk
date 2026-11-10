package io.github.rosyidputrar.duobackup.core.engine

import io.github.rosyidputrar.duobackup.core.catalog.RunResult
import io.github.rosyidputrar.duobackup.core.catalog.RunTrigger

/** What one backup run achieved. `pendingParts` above zero means there is work left for a follow-up run. */
data class SessionOutcome(
    val result: RunResult,
    val stopReason: String? = null,
    val bytesUploadedDrive: Long = 0,
    val bytesUploadedTg: Long = 0,
    val filesHashed: Int = 0,
    val errors: Int = 0,
    val pendingParts: Int = 0
)

/** One backup run. It stops starting new work once `budgetMs` is nearly used up. */
fun interface BackupSession {
    suspend fun run(trigger: RunTrigger, budgetMs: Long): SessionOutcome
}
