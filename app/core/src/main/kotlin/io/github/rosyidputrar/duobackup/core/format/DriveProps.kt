package io.github.rosyidputrar.duobackup.core.format

/**
 * The `appProperties` that identify DuoBackup's files and folders on Google Drive (plan §4.4).
 * Drive files are always found through these, never by name.
 */
object DriveProps {
    const val KEY_KIND: String = "db"
    const val KEY_VERSION: String = "v"
    const val VERSION: String = "1"

    const val KIND_ROOT: String = "root"
    const val KIND_DATA_DIR: String = "data"
    const val KIND_PART: String = "part"
    const val KIND_INDEX_DIR: String = "index-dir"
    const val KIND_INDEX: String = "index"
    const val KIND_KEY_DIR: String = "key-dir"
    const val KIND_KEY: String = "key"

    /** Drive allows 124 bytes per property; DuoBackup stays under 120. */
    const val MAX_PAIR_BYTES: Int = 120

    fun root(install: String): Map<String, String> =
        checked(KEY_KIND to KIND_ROOT, KEY_VERSION to VERSION, "install" to install)

    fun dataDir(): Map<String, String> = checked(KEY_KIND to KIND_DATA_DIR)

    /** @param partHash the part SHA-256, or its HMAC under the id key when encrypted */
    fun part(oid: String, partIndex: Int, partHash: String): Map<String, String> = checked(
        KEY_KIND to KIND_PART,
        KEY_VERSION to VERSION,
        "oid" to oid,
        "pi" to partIndex.toString(),
        "ph" to partHash
    )

    fun indexDir(): Map<String, String> = checked(KEY_KIND to KIND_INDEX_DIR)

    fun index(generatedAt: Long, keyId: String?): Map<String, String> = checked(
        *listOfNotNull(
            KEY_KIND to KIND_INDEX,
            KEY_VERSION to VERSION,
            "ts" to generatedAt.toString(),
            keyId?.let { "kid" to it }
        ).toTypedArray()
    )

    fun keyDir(): Map<String, String> = checked(KEY_KIND to KIND_KEY_DIR)

    fun key(keyId: String): Map<String, String> = checked(KEY_KIND to KIND_KEY, KEY_VERSION to VERSION, "kid" to keyId)

    private fun checked(vararg pairs: Pair<String, String>): Map<String, String> {
        for ((key, value) in pairs) {
            val bytes = key.toByteArray(Charsets.UTF_8).size + value.toByteArray(Charsets.UTF_8).size
            require(bytes <= MAX_PAIR_BYTES) { "Drive property '$key' is $bytes bytes, above $MAX_PAIR_BYTES" }
        }
        return linkedMapOf(*pairs)
    }
}
