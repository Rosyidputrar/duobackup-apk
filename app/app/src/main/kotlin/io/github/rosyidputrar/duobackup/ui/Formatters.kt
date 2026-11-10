package io.github.rosyidputrar.duobackup.ui

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.annotation.StringRes
import io.github.rosyidputrar.duobackup.R
import io.github.rosyidputrar.duobackup.core.catalog.RunResult
import io.github.rosyidputrar.duobackup.core.catalog.RunTrigger

fun formatBytes(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

fun formatDateTime(context: Context, epochMs: Long): String = DateUtils.formatDateTime(
    context,
    epochMs,
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
)

@StringRes
fun resultLabel(result: RunResult?): Int = when (result) {
    RunResult.OK -> R.string.result_ok
    RunResult.PARTIAL -> R.string.result_partial
    RunResult.FAILED -> R.string.result_failed
    null -> R.string.result_running
}

@StringRes
fun triggerLabel(trigger: RunTrigger): Int = when (trigger) {
    RunTrigger.MANUAL -> R.string.trigger_manual
    RunTrigger.SCHEDULED -> R.string.trigger_scheduled
}
