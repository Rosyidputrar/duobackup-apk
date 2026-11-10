package io.github.rosyidputrar.duobackup.storage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/**
 * Access to shared storage. Android 11 and later need All Files Access, granted on a system
 * settings screen. Android 8 to 10 use the classic storage permission instead.
 */
object StorageAccess {
    val usesAllFilesAccess: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** Permissions to request at runtime on Android 10 and earlier. */
    val legacyPermissions: Array<String> =
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)

    fun isGranted(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        legacyPermissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    /**
     * Intents that open the All Files Access screen, most specific first. The app-specific screen
     * is missing on some devices, so the caller falls back to the general list.
     */
    fun allFilesAccessIntents(context: Context): List<Intent> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:${context.packageName}".toUri()),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        )
    }
}
