package io.github.rosyidputrar.duobackup.core.golden

import java.io.File
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * Writes golden files that do not exist yet. Existing files are never touched: a golden vector
 * is a promise that data written by an old version stays readable, so a format change adds new
 * vectors under new names instead of replacing these.
 *
 * To add a vector, describe it in [GoldenVectors.files], then run from `app/`:
 *
 * ```
 * ./gradlew :core:test --tests '*GoldenGenerator*' -PregenerateGolden
 * ```
 *
 * The `regenerateGolden` property lifts `@Disabled` for that run and points the generator at
 * `core/src/test/resources/golden`. Commit the new files together with the test that reads them.
 */
class GoldenGenerator {
    @Test
    @Disabled("run on demand, see the class comment")
    fun `write missing golden files`() {
        val dir = File(checkNotNull(System.getProperty("golden.dir")) { "golden.dir is not set" })
        check(dir.isDirectory || dir.mkdirs()) { "cannot create $dir" }
        for ((name, bytes) in GoldenVectors.files()) {
            val file = File(dir, name)
            if (file.exists()) continue
            file.writeBytes(bytes)
            println("wrote ${file.name} (${bytes.size} bytes)")
        }
    }
}
