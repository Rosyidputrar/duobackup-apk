package io.github.rosyidputrar.duobackup.core.format

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class RemoteFormatsTest {
    private val oid = "9f2c0a1b33d4e5f60718293a4b5c6d7e8f909192a3b4c5d6e7f8091a2b3c4d5e"
    private val install = "0b9d5c2e-6a1f-4c1e-9b7a-3f2d1e0c9b8a"

    @Test
    fun `drive properties match the documented layout`() {
        assertEquals(mapOf("db" to "root", "v" to "1", "install" to install), DriveProps.root(install))
        assertEquals(mapOf("db" to "data"), DriveProps.dataDir())
        assertEquals(mapOf("db" to "index-dir"), DriveProps.indexDir())
        assertEquals(mapOf("db" to "key-dir"), DriveProps.keyDir())
        assertEquals(
            mapOf("db" to "part", "v" to "1", "oid" to oid, "pi" to "3", "ph" to oid.reversed()),
            DriveProps.part(oid, 3, oid.reversed())
        )
        assertEquals(
            mapOf("db" to "index", "v" to "1", "ts" to "1762758000000"),
            DriveProps.index(1_762_758_000_000, null)
        )
        assertEquals(
            mapOf("db" to "index", "v" to "1", "ts" to "1762758000000", "kid" to "0123456789abcdef"),
            DriveProps.index(1_762_758_000_000, "0123456789abcdef")
        )
        assertEquals(mapOf("db" to "key", "v" to "1", "kid" to "0123456789abcdef"), DriveProps.key("0123456789abcdef"))
    }

    @Test
    fun `worst case drive properties stay under 120 bytes per pair`() {
        val props = DriveProps.part(oid, Int.MAX_VALUE, oid) + DriveProps.index(Long.MAX_VALUE, "f".repeat(16)) +
            DriveProps.root(install)
        for ((key, value) in props) {
            assertTrue(key.toByteArray().size + value.toByteArray().size <= 120, key)
        }
    }

    @Test
    fun `oversized drive property is refused`() {
        assertFailsWith<IllegalArgumentException> { DriveProps.root("x".repeat(114)) }
        assertFailsWith<IllegalArgumentException> { DriveProps.key("é".repeat(60)) }
        assertEquals(113, DriveProps.root("x".repeat(113)).getValue("install").length)
    }

    @Test
    fun `remote names never use original file names`() {
        assertEquals("9f2c0a1b33d4e5f6.0.dbp", RemoteNames.part(oid, 0))
        assertEquals("9f2c0a1b33d4e5f6.39.dbp", RemoteNames.part(oid, 39))
        assertFailsWith<IllegalArgumentException> { RemoteNames.part("abc", 0) }
        assertFailsWith<IllegalArgumentException> { RemoteNames.part(oid, -1) }
        assertEquals(
            "duobackup-index-20261110T070000Z.json.gz",
            RemoteNames.index(1_794_294_000_000, encrypted = false)
        )
        assertEquals(
            "duobackup-index-20261110T070000Z.json.gz.dbe",
            RemoteNames.index(1_794_294_000_999, encrypted = true)
        )
        assertEquals("duobackup-key.json", RemoteNames.KEY_FILE)
        assertEquals("DuoBackup", RemoteNames.STORAGE_TITLE)
    }

    @Test
    fun `storage marker round trips inside a description`() {
        assertEquals("#duobackup-storage v1 $install", TelegramTags.storage(install))
        assertEquals(install, TelegramTags.parseStorage(TelegramTags.storage(install)))
        assertEquals(install, TelegramTags.parseStorage("My backups\n  #duobackup-storage v1 $install \nDo not delete"))
        assertNull(TelegramTags.parseStorage("just a channel"))
        assertNull(TelegramTags.parseStorage("#duobackup-storage v2 $install"))
        assertNull(TelegramTags.parseStorage("#duobackup-storage v1"))
    }

    @Test
    fun `index marker round trips with and without a key id`() {
        assertEquals("#duobackup-index v1 1762758000000", TelegramTags.index(1_762_758_000_000, null))
        assertEquals(
            "#duobackup-index v1 1762758000000 kid=0123456789abcdef",
            TelegramTags.index(1_762_758_000_000, "0123456789abcdef")
        )
        assertEquals(
            TelegramTags.IndexTag(1_762_758_000_000, null),
            TelegramTags.parseIndex("#duobackup-index v1 1762758000000")
        )
        assertEquals(
            TelegramTags.IndexTag(1_762_758_000_000, "0123456789abcdef"),
            TelegramTags.parseIndex(TelegramTags.index(1_762_758_000_000, "0123456789abcdef"))
        )
        for (bad in listOf(
            "",
            "#duobackup-index",
            "#duobackup-index v2 1",
            "#duobackup-index v1 soon",
            "#duobackup-index v1 1 key=abc",
            "#duobackup-index v1 1 kid=",
            "#duobackup-index v1 1 kid=a extra",
            "#duobackup-key v1 abc"
        )) {
            assertNull(TelegramTags.parseIndex(bad), bad)
        }
    }

    @Test
    fun `key marker round trips`() {
        assertEquals("#duobackup-key v1 0123456789abcdef", TelegramTags.key("0123456789abcdef"))
        assertEquals("0123456789abcdef", TelegramTags.parseKey("#duobackup-key v1 0123456789abcdef"))
        assertNull(TelegramTags.parseKey("#duobackup-key v2 0123456789abcdef"))
        assertNull(TelegramTags.parseKey("#duobackup-index v1 1"))
    }
}
