package io.github.rosyidputrar.duobackup.core.util

import java.io.File

/** App-controlled temporary storage for per-part scratch files. */
interface ScratchSpace {
    fun newFile(name: String): File

    fun freeBytes(): Long
}
