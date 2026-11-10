package io.github.rosyidputrar.duobackup.ui

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import io.github.rosyidputrar.duobackup.core.catalog.FileRecord
import io.github.rosyidputrar.duobackup.core.catalog.FolderEntry
import io.github.rosyidputrar.duobackup.data.db.CatalogDatabase
import io.github.rosyidputrar.duobackup.data.db.RoomCatalog
import io.github.rosyidputrar.duobackup.ui.browse.BrowseState
import io.github.rosyidputrar.duobackup.ui.browse.BrowseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BrowseViewModelTest {
    private lateinit var db: CatalogDatabase
    private lateinit var catalog: RoomCatalog

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CatalogDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        catalog = RoomCatalog(db)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun file(path: String, size: Long) =
        FileRecord(volume = "primary", path = path, size = size, mtime = 1, lastSeenScan = 1)

    @Test
    fun `walks down into folders and back up to the root`() = runTest {
        catalog.upsertFiles(listOf(file("DCIM/Camera/a.jpg", 10), file("DCIM/b.jpg", 5), file("top.txt", 1)))
        val viewModel = BrowseViewModel(catalog)
        val dcim = FolderEntry("DCIM", true, 2, 15)
        val camera = FolderEntry("Camera", true, 1, 10)
        viewModel.state.test {
            // The initial empty state may or may not be seen before the first query lands.
            suspend fun awaitFolder(folder: String, entries: List<FolderEntry>) {
                val expected = BrowseState(folder, entries)
                while (true) if (awaitItem() == expected) return
            }
            awaitFolder("", listOf(dcim, FolderEntry("top.txt", false, 1, 1)))

            viewModel.open(FolderEntry("top.txt", false, 1, 1))
            expectNoEvents()

            viewModel.open(dcim)
            awaitFolder("DCIM", listOf(camera, FolderEntry("b.jpg", false, 1, 5)))
            viewModel.open(camera)
            awaitFolder("DCIM/Camera", listOf(FolderEntry("a.jpg", false, 1, 10)))

            assertTrue(viewModel.up())
            awaitFolder("DCIM", listOf(camera, FolderEntry("b.jpg", false, 1, 5)))
            assertTrue(viewModel.up())
            awaitFolder("", listOf(dcim, FolderEntry("top.txt", false, 1, 1)))
            assertFalse(viewModel.up())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
