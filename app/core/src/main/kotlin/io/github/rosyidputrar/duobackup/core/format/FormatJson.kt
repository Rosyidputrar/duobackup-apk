package io.github.rosyidputrar.duobackup.core.format

import kotlinx.serialization.json.Json

/** Shared JSON configuration: compact, tolerant of unknown keys, nulls omitted. */
internal val FormatJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
