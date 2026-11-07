package dev.shehran.rawcull.importer

import dev.shehran.rawcull.data.model.ImportItemRole
import dev.shehran.rawcull.data.scan.AssetDraft
import dev.shehran.rawcull.data.scan.ScannedEntry
import dev.shehran.rawcull.data.scan.WalkResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ImportSessionTest {
    private val source = ImportSource("tree://card", "SD card", "ABCD-1234")

    private class FakeReader : ImportSourceReader {
        var entries: List<ScannedEntry> = emptyList()
        var available = true
        var listFailure: IOException? = null
        var metadataFailure: IOException? = null
        var failMetadataFor: String? = null
        val dates = HashMap<String, Long>()
        val hashed = ArrayList<String>()
        var lists = 0

        override suspend fun list(treeUri: String, onProgress: (Int, Int) -> Unit): WalkResult {
            lists++
            listFailure?.let { throw it }
            onProgress(1, entries.size)
            return WalkResult(entries, 1)
        }

        override fun metadata(draft: AssetDraft): SourceMetadata {
            if (metadataFailure != null && (failMetadataFor == null || failMetadataFor == draft.primary.name)) {
                throw metadataFailure!!
            }
            return SourceMetadata(dates[draft.primary.name], "{}")
        }

        override fun headHash(uri: String): String {
            hashed += uri
            return "hash:$uri"
        }

        override fun isAvailable(treeUri: String) = available
    }

    private fun entry(name: String, size: Long = 100L, modified: Long = 1L) = ScannedEntry(
        docId = name,
        docUri = "doc://$name",
        parentDocId = "dcim",
        parentDocUri = "doc://dcim",
        relativeDir = "DCIM",
        name = name,
        mime = null,
        size = size,
        lastModified = modified,
    )

    private val reader = FakeReader()
    private val index = HashSet<Pair<String, Long>>()

    private fun TestScope.session() = ImportSession(
        reader = reader,
        knownSizes = { index.map { it.second }.toSet() },
        isIndexed = { hash, size -> (hash to size) in index },
        scope = this,
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun `pairs and sidecars become one candidate`() = runTest {
        reader.entries = listOf(entry("DSC1.ARW"), entry("DSC1.JPG"), entry("DSC1.xmp"))
        val session = session()

        session.start(source)
        advanceUntilIdle()

        val candidate = session.state.value.candidateList.single()
        assertEquals(listOf("DSC1.ARW", "DSC1.JPG", "DSC1.xmp"), candidate.files.map { it.name })
        assertEquals(
            listOf(ImportItemRole.PRIMARY, ImportItemRole.COMPANION, ImportItemRole.ADOBE_SIDECAR),
            candidate.files.map { it.role },
        )
    }

    @Test
    fun `dates come from the file's metadata, with its modification time as the fallback`() = runTest {
        reader.entries = listOf(entry("A.ARW", modified = 5L), entry("B.ARW", modified = 6L))
        reader.dates["A.ARW"] = 99L
        val session = session()

        session.start(source)
        advanceUntilIdle()

        val byName = session.state.value.candidateList.associateBy { it.files.first().name }
        assertEquals(99L, byName.getValue("A.ARW").folderDate)
        assertNull(byName.getValue("B.ARW").captureDate)
        assertEquals(6L, byName.getValue("B.ARW").folderDate)
    }

    @Test
    fun `candidates are published newest first, before their dates are read`() = runTest {
        reader.entries = listOf(entry("OLD.ARW", modified = 1L), entry("NEW.ARW", modified = 2L))
        val session = session()
        val seen = ArrayList<ImportSessionState>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            session.state.collect { seen += it }
        }

        session.start(source)
        advanceUntilIdle()
        collector.cancel()

        val reading = seen.filterIsInstance<ImportSessionState.Reading>().first()
        assertEquals(listOf("NEW.ARW", "OLD.ARW"), reading.candidates.map { it.files.first().name })
        assertEquals(2, reading.total)
        assertTrue(seen.last() is ImportSessionState.Ready)
    }

    @Test
    fun `an unknown size is never hashed`() = runTest {
        reader.entries = listOf(entry("A.ARW", size = 100L))
        index += "x" to 55L
        val session = session()

        session.start(source)
        advanceUntilIdle()

        assertTrue(reader.hashed.isEmpty())
        assertFalse(session.state.value.candidateList.single().files.single().alreadyImported)
    }

    @Test
    fun `a file in the index is marked as imported`() = runTest {
        reader.entries = listOf(entry("A.ARW", size = 100L), entry("A.JPG", size = 200L))
        index += "hash:doc://A.ARW" to 100L
        val session = session()

        session.start(source)
        advanceUntilIdle()

        val files = session.state.value.candidateList.single().files.associateBy { it.name }
        assertTrue(files.getValue("A.ARW").alreadyImported)
        assertFalse(files.getValue("A.JPG").alreadyImported)
    }

    @Test
    fun `sidecars aren't checked against the index`() = runTest {
        reader.entries = listOf(entry("A.ARW", size = 1L), entry("A.xmp", size = 100L))
        index += "x" to 100L
        val session = session()

        session.start(source)
        advanceUntilIdle()

        assertTrue(reader.hashed.isEmpty())
    }

    @Test
    fun `a source without photos is empty`() = runTest {
        reader.entries = listOf(entry("notes.txt"))
        val session = session()

        session.start(source)
        advanceUntilIdle()

        assertEquals(ImportSessionState.Empty, session.state.value)
    }

    @Test
    fun `an empty listing from a vanished card is a lost source, not an empty one`() = runTest {
        reader.available = false
        val session = session()

        session.start(source)
        advanceUntilIdle()

        assertEquals(ImportSessionState.SourceLost, session.state.value)
    }

    @Test
    fun `a failed walk loses the source without crashing`() = runTest {
        reader.listFailure = FileNotFoundException("gone")
        val session = session()

        session.start(source)
        advanceUntilIdle()

        assertEquals(ImportSessionState.SourceLost, session.state.value)
    }

    @Test
    fun `a card pulled during the metadata reads loses the source`() = runTest {
        reader.entries = listOf(entry("A.ARW"))
        reader.metadataFailure = IOException("gone")
        reader.available = false
        val session = session()

        session.start(source)
        advanceUntilIdle()

        assertEquals(ImportSessionState.SourceLost, session.state.value)
    }

    @Test
    fun `one unreadable file doesn't stop the scan`() = runTest {
        reader.entries = listOf(entry("BAD.ARW", modified = 3L), entry("GOOD.ARW", modified = 4L))
        reader.metadataFailure = IOException("bad sector")
        reader.failMetadataFor = "BAD.ARW"
        reader.dates["GOOD.ARW"] = 9L
        val session = session()

        session.start(source)
        advanceUntilIdle()

        val state = session.state.value as ImportSessionState.Ready
        val byName = state.candidates.associateBy { it.files.first().name }
        assertEquals(3L, byName.getValue("BAD.ARW").folderDate)
        assertEquals(9L, byName.getValue("GOOD.ARW").folderDate)
    }

    @Test
    fun `an idle card that goes away is noticed`() = runTest {
        reader.entries = listOf(entry("A.ARW"))
        val session = session()
        session.start(source)
        advanceUntilIdle()

        reader.available = false
        session.onStorageChanged()
        advanceUntilIdle()

        assertEquals(ImportSessionState.SourceLost, session.state.value)
    }

    @Test
    fun `reinserting the card restores the session`() = runTest {
        reader.entries = listOf(entry("A.ARW"))
        val session = session()
        session.start(source)
        advanceUntilIdle()
        reader.available = false
        session.onStorageChanged()
        advanceUntilIdle()

        reader.available = true
        session.onStorageChanged()
        advanceUntilIdle()

        assertTrue(session.state.value is ImportSessionState.Ready)
        assertEquals(2, reader.lists)
    }

    @Test
    fun `a storage event with the card still there doesn't rescan`() = runTest {
        reader.entries = listOf(entry("A.ARW"))
        val session = session()
        session.start(source)
        advanceUntilIdle()

        session.onStorageChanged()
        advanceUntilIdle()

        assertEquals(1, reader.lists)
    }

    @Test
    fun `candidate ids stay the same across a rescan, so ticks survive it`() = runTest {
        reader.entries = listOf(entry("A.ARW"))
        val session = session()
        session.start(source)
        advanceUntilIdle()
        val firstId = session.state.value.candidateList.single().id

        session.start(source)
        advanceUntilIdle()

        assertEquals(firstId, session.state.value.candidateList.single().id)
    }

    @Test
    fun `clearing forgets the source`() = runTest {
        reader.entries = listOf(entry("A.ARW"))
        val session = session()
        session.start(source)
        advanceUntilIdle()

        session.clear()
        session.onStorageChanged()
        advanceUntilIdle()

        assertEquals(ImportSessionState.Idle, session.state.value)
    }
}
