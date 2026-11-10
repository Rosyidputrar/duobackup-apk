package io.github.rosyidputrar.duobackup.data.db

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import io.github.rosyidputrar.duobackup.core.catalog.ContentCompleteness
import io.github.rosyidputrar.duobackup.core.catalog.ContentPartRecord
import io.github.rosyidputrar.duobackup.core.catalog.ContentRecord
import io.github.rosyidputrar.duobackup.core.catalog.FileRecord
import io.github.rosyidputrar.duobackup.core.catalog.FileState
import io.github.rosyidputrar.duobackup.core.catalog.FileVersionRecord
import io.github.rosyidputrar.duobackup.core.catalog.FolderEntry
import io.github.rosyidputrar.duobackup.core.catalog.GcState
import io.github.rosyidputrar.duobackup.core.catalog.PartLocationRecord
import io.github.rosyidputrar.duobackup.core.catalog.RunResult
import io.github.rosyidputrar.duobackup.core.catalog.RunSummary
import io.github.rosyidputrar.duobackup.core.catalog.RunTrigger
import io.github.rosyidputrar.duobackup.core.model.DestinationId
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CatalogDatabaseTest {
    private lateinit var db: CatalogDatabase
    private lateinit var catalog: RoomCatalog

    private val shaA = "a".repeat(64)
    private val shaB = "b".repeat(64)
    private val shaC = "c".repeat(64)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CatalogDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        catalog = RoomCatalog(db)
    }

    @After
    fun tearDown() = db.close()

    private fun file(path: String, size: Long = 10, state: FileState = FileState.NEEDS_HASH, scan: Long = 1) =
        FileRecord(volume = "primary", path = path, size = size, mtime = 1000, state = state, lastSeenScan = scan)

    private fun content(sha: String, parts: Int, createdAt: Long = 1, gcState: GcState = GcState.LIVE) = ContentRecord(
        sha256 = sha,
        oid = "o$sha".take(64),
        size = parts * 100L,
        partSize = 100,
        partCount = parts,
        encrypted = false,
        mime = "image/jpeg",
        createdAt = createdAt,
        gcState = gcState
    )

    private fun parts(sha: String, count: Int) = List(count) {
        ContentPartRecord(sha, it, it * 100L, 100, "p$it$sha".take(64))
    }

    private fun driveLocation(sha: String, index: Int, missing: Boolean = false) = PartLocationRecord(
        sha256 = sha,
        partIndex = index,
        dest = DestinationId.DRIVE,
        storedSize = 100,
        driveFileId = "drive-$index",
        uploadedAt = 5,
        missing = missing
    )

    private suspend fun insert(sha: String, partCount: Int, createdAt: Long = 1) =
        catalog.insertContent(content(sha, partCount, createdAt), parts(sha, partCount))

    @Test
    fun `upsert inserts new paths and updates known ones without changing their id`() = runTest {
        catalog.upsertFiles(listOf(file("DCIM/a.jpg"), file("DCIM/b.jpg")))
        val first = catalog.fileByPath("primary", "DCIM/a.jpg")!!
        assertTrue(first.id > 0)
        assertEquals(FileState.NEEDS_HASH, first.state)
        assertFalse(first.hold)

        catalog.upsertFiles(
            listOf(
                file(
                    "DCIM/a.jpg",
                    size = 99,
                    state = FileState.HASHED,
                    scan = 2
                ).copy(currentSha256 = shaA, hold = true)
            )
        )
        val updated = catalog.fileByPath("primary", "DCIM/a.jpg")!!
        assertEquals(first.id, updated.id)
        assertEquals(99L, updated.size)
        assertEquals(FileState.HASHED, updated.state)
        assertEquals(shaA, updated.currentSha256)
        assertEquals(2L, updated.lastSeenScan)
        assertTrue(updated.hold)
        assertNull(catalog.fileByPath("primary", "missing"))
        assertNull(catalog.fileByPath("other-volume", "DCIM/a.jpg"))
    }

    @Test
    fun `a path is unique per volume`() = runTest {
        val dao = db.fileDao()
        val entity = FileEntity(0, "primary", "a", 1, 1, null, FileState.NEEDS_HASH, null, 1)
        dao.insert(entity)
        dao.insert(entity.copy(volume = "1234-ABCD"))
        assertThrows(SQLiteConstraintException::class.java) { kotlinx.coroutines.runBlocking { dao.insert(entity) } }
    }

    @Test
    fun `files are listed by state with a limit`() = runTest {
        catalog.upsertFiles(
            listOf(
                file("a"),
                file("b", state = FileState.HASHED),
                file("c"),
                file("d", state = FileState.ERROR),
                file("e")
            )
        )
        assertEquals(listOf("a", "c", "e"), catalog.filesByState(FileState.NEEDS_HASH, 10).map { it.path })
        assertEquals(listOf("a", "c"), catalog.filesByState(FileState.NEEDS_HASH, 2).map { it.path })
        assertEquals(listOf("d"), catalog.filesByState(FileState.ERROR, 10).map { it.path })
        assertEquals(emptyList<String>(), catalog.filesByState(FileState.DELETED, 10).map { it.path })
    }

    @Test
    fun `folder listing groups sub folders and leaves deleted files out`() = runTest {
        catalog.upsertFiles(
            listOf(
                file("top.txt", size = 1),
                file("DCIM/Camera/a.jpg", size = 10),
                file("DCIM/Camera/b.jpg", size = 20),
                file("DCIM/c.jpg", size = 5),
                file("DCIM/gone.jpg", size = 500, state = FileState.DELETED),
                file("DCIM_other/x.bin", size = 7),
                file("100%_done/y.bin", size = 3),
                file("Deleted/only.bin", size = 9, state = FileState.DELETED)
            )
        )
        catalog.upsertFiles(listOf(file("DCIM/Camera/a.jpg", size = 10).copy(volume = "1234-ABCD")))

        assertEquals(
            listOf(
                FolderEntry("100%_done", true, 1, 3),
                FolderEntry("DCIM", true, 3, 35),
                FolderEntry("DCIM_other", true, 1, 7),
                FolderEntry("top.txt", false, 1, 1)
            ),
            catalog.observeFolder("primary", "").first()
        )
        assertEquals(
            listOf(FolderEntry("Camera", true, 2, 30), FolderEntry("c.jpg", false, 1, 5)),
            catalog.observeFolder("primary", "DCIM").first()
        )
        assertEquals(
            listOf(FolderEntry("a.jpg", false, 1, 10), FolderEntry("b.jpg", false, 1, 20)),
            catalog.observeFolder("primary", "DCIM/Camera").first()
        )
        assertEquals(listOf(FolderEntry("y.bin", false, 1, 3)), catalog.observeFolder("primary", "100%_done").first())
        assertEquals(emptyList<FolderEntry>(), catalog.observeFolder("primary", "Nope").first())
        assertEquals(listOf(FolderEntry("DCIM", true, 1, 10)), catalog.observeFolder("1234-ABCD", "").first())
    }

    @Test
    fun `folder listing updates when files change`() = runTest {
        catalog.observeFolder("primary", "").test {
            assertEquals(emptyList<FolderEntry>(), awaitItem())
            catalog.upsertFiles(listOf(file("a.txt", size = 4)))
            assertEquals(listOf(FolderEntry("a.txt", false, 1, 4)), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `content is stored with its parts once`() = runTest {
        insert(shaA, 2)
        catalog.insertContent(content(shaA, 2).copy(mime = "changed"), parts(shaA, 3))
        assertEquals("image/jpeg", catalog.contentBySha256(shaA)!!.mime)
        assertEquals(
            parts(shaA, 2),
            db.contentDao().partsOf(shaA).map {
                ContentPartRecord(it.sha256, it.partIndex, it.plainOffset, it.plainSize, it.partSha256)
            }
        )
        assertNull(catalog.contentBySha256(shaB))
    }

    @Test
    fun `a part location needs its part to exist`() = runTest {
        assertThrows(SQLiteConstraintException::class.java) {
            kotlinx.coroutines.runBlocking { catalog.recordPartLocation(driveLocation(shaA, 0)) }
        }
        insert(shaA, 1)
        assertThrows(SQLiteConstraintException::class.java) {
            kotlinx.coroutines.runBlocking { catalog.recordPartLocation(driveLocation(shaA, 7)) }
        }
        catalog.recordPartLocation(driveLocation(shaA, 0))
        assertEquals(1, db.contentDao().locationsOf(shaA).size)
    }

    @Test
    fun `recording a location again replaces it`() = runTest {
        insert(shaA, 1)
        catalog.recordPartLocation(driveLocation(shaA, 0, missing = true))
        catalog.recordPartLocation(driveLocation(shaA, 0).copy(driveFileId = "new", verifiedAt = 9))
        val stored = db.contentDao().locationsOf(shaA).single()
        assertEquals("new", stored.driveFileId)
        assertEquals(9L, stored.verifiedAt)
        assertFalse(stored.missing)
    }

    @Test
    fun `pending parts are those without a usable location on that destination`() = runTest {
        insert(shaA, 3, createdAt = 1)
        insert(shaB, 1, createdAt = 2)
        catalog.insertContent(content(shaC, 1, createdAt = 3, gcState = GcState.DELETING), parts(shaC, 1))
        catalog.recordPartLocation(driveLocation(shaA, 0))
        catalog.recordPartLocation(driveLocation(shaA, 2, missing = true))
        catalog.recordPartLocation(
            PartLocationRecord(
                shaB,
                0,
                DestinationId.TELEGRAM,
                100,
                tgChatId = -1,
                tgMessageId = 2,
                tgRemoteFileId = "f",
                uploadedAt = 5
            )
        )

        assertEquals(
            listOf(shaA to 1, shaA to 2, shaB to 0),
            catalog.pendingParts(DestinationId.DRIVE, 10).map { it.sha256 to it.partIndex }
        )
        assertEquals(listOf(shaA to 1), catalog.pendingParts(DestinationId.DRIVE, 1).map { it.sha256 to it.partIndex })
        assertEquals(
            listOf(shaA to 0, shaA to 1, shaA to 2),
            catalog.pendingParts(DestinationId.TELEGRAM, 10).map { it.sha256 to it.partIndex }
        )
        assertEquals(300L, catalog.observePendingBytes(DestinationId.DRIVE).first())
        assertEquals(300L, catalog.observePendingBytes(DestinationId.TELEGRAM).first())
    }

    @Test
    fun `pending bytes are zero for an empty catalog and follow new locations`() = runTest {
        catalog.observePendingBytes(DestinationId.DRIVE).test {
            assertEquals(0L, awaitItem())
            insert(shaA, 2)
            assertEquals(200L, awaitItem())
            catalog.recordPartLocation(driveLocation(shaA, 0))
            assertEquals(100L, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `completeness counts located parts per destination`() = runTest {
        insert(shaA, 2, createdAt = 1)
        insert(shaB, 1, createdAt = 2)
        catalog.insertContent(content(shaC, 0, createdAt = 3), emptyList())
        catalog.recordPartLocation(driveLocation(shaA, 0))
        catalog.recordPartLocation(driveLocation(shaB, 0))
        val drive = catalog.contentCompleteness(DestinationId.DRIVE)
        assertEquals(
            listOf(ContentCompleteness(shaA, 2, 1), ContentCompleteness(shaB, 1, 1), ContentCompleteness(shaC, 0, 0)),
            drive
        )
        assertEquals(listOf(false, true, true), drive.map { it.complete })
        assertEquals(listOf(0, 0, 0), catalog.contentCompleteness(DestinationId.TELEGRAM).map { it.locatedParts })
    }

    @Test
    fun `versions are live in the half open interval from first seen to superseded`() = runTest {
        catalog.upsertFiles(listOf(file("a"), file("b")))
        val a = catalog.fileByPath("primary", "a")!!
        val b = catalog.fileByPath("primary", "b")!!
        insert(shaA, 1)
        insert(shaB, 1)
        catalog.addVersion(FileVersionRecord(a.id, shaA, mtime = 1, firstSeen = 100))
        catalog.supersedeCurrentVersion(a.id, 200)
        catalog.addVersion(FileVersionRecord(a.id, shaB, mtime = 2, firstSeen = 200))
        catalog.addVersion(FileVersionRecord(b.id, shaA, mtime = 3, firstSeen = 150))
        catalog.supersedeCurrentVersion(b.id, 300)

        fun live(time: Long) = kotlinx.coroutines.runBlocking {
            catalog.versionsLiveAt(time).map {
                it.file.path to
                    it.version.sha256
            }
        }
        assertEquals(emptyList<Pair<String, String>>(), live(99))
        assertEquals(listOf("a" to shaA), live(100))
        assertEquals(listOf("a" to shaA, "b" to shaA), live(199))
        assertEquals(listOf("a" to shaB, "b" to shaA), live(200))
        assertEquals(listOf("a" to shaB), live(300))
        assertEquals(2, db.versionDao().versionsOf(a.id).size)
        assertEquals(a, catalog.versionsLiveAt(300).single().file)
    }

    @Test
    fun `a version needs its file and its content`() = runTest {
        catalog.upsertFiles(listOf(file("a")))
        val a = catalog.fileByPath("primary", "a")!!
        assertThrows(SQLiteConstraintException::class.java) {
            kotlinx.coroutines.runBlocking { catalog.addVersion(FileVersionRecord(a.id, shaA, 1, 1)) }
        }
        insert(shaA, 1)
        assertThrows(SQLiteConstraintException::class.java) {
            kotlinx.coroutines.runBlocking { catalog.addVersion(FileVersionRecord(a.id + 99, shaA, 1, 1)) }
        }
    }

    @Test
    fun `unreferenced contents are live contents without any version`() = runTest {
        catalog.upsertFiles(listOf(file("a")))
        val a = catalog.fileByPath("primary", "a")!!
        insert(shaA, 1, createdAt = 1)
        insert(shaB, 1, createdAt = 2)
        catalog.insertContent(content(shaC, 1, createdAt = 3, gcState = GcState.GONE), parts(shaC, 1))
        catalog.addVersion(FileVersionRecord(a.id, shaA, 1, 1, supersededAt = 2))
        assertEquals(listOf(shaB), catalog.unreferencedContents().map { it.sha256 })
    }

    @Test
    fun `runs are logged and listed newest first`() = runTest {
        val first = catalog.startRun(RunTrigger.SCHEDULED, 100)
        val second = catalog.startRun(RunTrigger.MANUAL, 200)
        catalog.finishRun(
            first,
            RunSummary(
                endedAt = 150,
                result = RunResult.PARTIAL,
                stopReason = "BUDGET",
                bytesUploadedDrive = 7,
                bytesUploadedTg = 8,
                filesHashed = 3,
                errors = 1
            )
        )
        val runs = catalog.observeRuns(10).first()
        assertEquals(listOf(second, first), runs.map { it.id })
        assertNull(runs[0].result)
        assertNull(runs[0].endedAt)
        assertEquals(RunTrigger.MANUAL, runs[0].trigger)
        val finished = runs[1]
        assertEquals(150L, finished.endedAt)
        assertEquals(RunResult.PARTIAL, finished.result)
        assertEquals("BUDGET", finished.stopReason)
        assertEquals(listOf(7L, 8L), listOf(finished.bytesUploadedDrive, finished.bytesUploadedTg))
        assertEquals(listOf(3, 1), listOf(finished.filesHashed, finished.errors))
        assertEquals(1, catalog.observeRuns(1).first().size)
    }

    @Test
    fun `scans record their completion`() = runTest {
        val id = catalog.startScan("DCIM", 10)
        assertNull(db.runDao().scanById(id)!!.completedAt)
        catalog.completeScan(id, 20)
        val scan = db.runDao().scanById(id)!!
        assertEquals(listOf("DCIM", 10L, 20L), listOf(scan.root, scan.startedAt, scan.completedAt))
    }

    @Test
    fun `snapshots are listed newest first and can be deleted`() = runTest {
        val dao = db.snapshotDao()
        val old = dao.insert(
            SnapshotEntity(
                createdAt = 1,
                fileCount = 1,
                totalBytes = 10,
                pendingParts = 0,
                driveFileId = "d",
                tgMessageId = null,
                formatVersion = 1
            )
        )
        val new = dao.insert(
            SnapshotEntity(
                createdAt = 2,
                fileCount = 2,
                totalBytes = 20,
                pendingParts = 3,
                driveFileId = null,
                tgMessageId = 9,
                formatVersion = 1
            )
        )
        assertEquals(listOf(new, old), dao.newestFirst().map { it.id })
        dao.delete(old)
        assertEquals(listOf(new), dao.newestFirst().map { it.id })
    }

    @Test
    fun `drive sessions are kept per part and expire by age`() = runTest {
        val dao = db.driveSessionDao()
        dao.upsert(
            DriveSessionEntity(shaA, 0, "https://upload/1", 100, createdAt = 10, failures = 0, scratchPath = "s0")
        )
        dao.upsert(
            DriveSessionEntity(shaA, 1, "https://upload/2", 100, createdAt = 50, failures = 0, scratchPath = "s1")
        )
        dao.upsert(
            DriveSessionEntity(shaA, 0, "https://upload/1", 100, createdAt = 10, failures = 2, scratchPath = "s0")
        )
        assertEquals(2, dao.find(shaA, 0)!!.failures)
        assertNull(dao.find(shaB, 0))
        assertEquals(1, dao.deleteOlderThan(20))
        assertNull(dao.find(shaA, 0))
        assertNotNull(dao.find(shaA, 1))
        dao.delete(shaA, 1)
        assertNull(dao.find(shaA, 1))
    }

    @Test
    fun `exported schema is version 1 and matches the running database`() {
        val schema = File("schemas/${CatalogDatabase::class.java.name}/1.json").readText()
        val identityHash = Regex("\"identityHash\": \"([0-9a-f]+)\"").find(schema)!!.groupValues[1]
        val cursor = db.openHelper.readableDatabase.query("SELECT identity_hash FROM room_master_table")
        cursor.use {
            assertTrue(it.moveToFirst())
            assertEquals(identityHash, it.getString(0))
        }
        val tables = Regex("\"tableName\": \"([a-z_]+)\"").findAll(schema).map { it.groupValues[1] }.toSet()
        val expected = setOf("files", "contents", "content_parts", "part_locations", "file_versions") +
            setOf("drive_sessions", "snapshots", "runs", "scans")
        assertEquals(expected, tables)
    }
}
