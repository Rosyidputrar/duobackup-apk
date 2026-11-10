package io.github.rosyidputrar.duobackup.core.format

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The manifest carried by every Telegram part message (plan §4.3). */
@Serializable
data class PartCaption(
    @SerialName("v") val version: Int = VERSION,
    @SerialName("o") val oid: String,
    @SerialName("h") val contentSha256: String,
    @SerialName("s") val contentSize: Long,
    @SerialName("ps0") val contentPartSize: Long,
    @SerialName("pc") val partCount: Int,
    @SerialName("pi") val partIndex: Int,
    @SerialName("po") val plainOffset: Long,
    @SerialName("pl") val plainSize: Long,
    @SerialName("ph") val partSha256: String,
    @SerialName("e") val encrypted: Int,
    @SerialName("p") val path: String,
    @SerialName("vol") val volume: String,
    @SerialName("mt") val mtime: Long,
    @SerialName("m") val mime: String? = null,
    @SerialName("pt") val pathTruncated: Int? = null
) {
    companion object {
        const val VERSION: Int = 1
    }
}
