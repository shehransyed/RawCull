package dev.shehran.rawcull.importer

import dev.shehran.rawcull.data.model.ImportItemRole
import dev.shehran.rawcull.data.model.PhotoFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportPlannerTest {
    private fun image(name: String, role: ImportItemRole = ImportItemRole.PRIMARY, size: Long = 100L) = CandidateFile(
        uri = "card://$name",
        name = name,
        sizeBytes = size,
        role = role,
        format = PhotoFormat.fromFileName(name),
    )

    private fun sidecar(name: String, role: ImportItemRole = ImportItemRole.ADOBE_SIDECAR) =
        CandidateFile(uri = "card://$name", name = name, sizeBytes = 5L, role = role)

    private fun candidate(
        stem: String,
        vararg files: CandidateFile,
        date: Long? = 1L,
        id: String = stem,
        stemGroup: String = stem,
    ) = ImportCandidate(id = id, stemGroup = stemGroup, stem = stem, files = files.toList(), captureDate = date)

    private fun pair(stem: String, date: Long? = 1L) = candidate(
        stem,
        image("$stem.ARW"),
        image("$stem.JPG", ImportItemRole.COMPANION),
        sidecar("$stem.xmp"),
        date = date,
    )

    private fun plan(
        candidates: List<ImportCandidate>,
        selected: Set<String> = candidates.map { it.id }.toSet(),
        includeRaw: Boolean = true,
        includeRaster: Boolean = true,
        existing: Map<String, ExistingTarget> = emptyMap(),
        sourceHead: (CandidateFile) -> String? = { "same" },
        template: (Long?) -> String = { "2026" },
    ) = ImportPlanner.plan(
        candidates,
        ImportSelection(selected, includeRaw, includeRaster),
        template,
        { dir, name -> existing["$dir/${name.lowercase()}"] },
        sourceHead,
    )

    private fun existingFile(size: Long = 100L, head: String? = "same") = ExistingTarget("lib://x", size) { head }

    @Test
    fun `a selected pair expands to its files`() {
        val items = plan(listOf(pair("DSC1")))

        assertEquals(listOf("DSC1.ARW", "DSC1.JPG", "DSC1.xmp"), items.map { it.targetName })
        assertEquals(
            listOf(ImportItemRole.PRIMARY, ImportItemRole.COMPANION, ImportItemRole.ADOBE_SIDECAR),
            items.map { it.role },
        )
        assertTrue(items.all { it.targetRelativeDir == "2026" && it.groupKey == "DSC1" && !it.skippedDuplicate })
    }

    @Test
    fun `only selected candidates are planned`() {
        val items = plan(listOf(pair("DSC1"), pair("DSC2")), selected = setOf("DSC2"))
        assertTrue(items.all { it.groupKey == "DSC2" })
    }

    @Test
    fun `earlier photos are copied first and undated ones last`() {
        val items = plan(listOf(pair("LATE", 20L), pair("NONE", null), pair("EARLY", 10L)))
        assertEquals(listOf("EARLY", "LATE", "NONE"), items.map { it.groupKey }.distinct())
    }

    @Test
    fun `the target folder follows the capture date`() {
        val items = plan(listOf(pair("A", 1L), pair("B", 2L)), template = { "day-$it" })
        assertEquals(setOf("day-1"), items.filter { it.groupKey == "A" }.map { it.targetRelativeDir }.toSet())
        assertEquals(setOf("day-2"), items.filter { it.groupKey == "B" }.map { it.targetRelativeDir }.toSet())
    }

    @Test
    fun `the file date stands in for a missing capture date`() {
        val item = candidate("A", image("A.ARW"), date = null).copy(fallbackDate = 7L)
        assertEquals("day-7", plan(listOf(item), template = { "day-$it" }).single().targetRelativeDir)
    }

    @Test
    fun `turning raster off keeps the RAW of a pair`() {
        val items = plan(listOf(pair("DSC1")), includeRaster = false)
        assertEquals(listOf("DSC1.ARW", "DSC1.xmp"), items.map { it.targetName })
    }

    @Test
    fun `turning raster off drops JPEG and HEIF companions and raster-only photos`() {
        val heifPair = candidate("H1", image("H1.ARW"), image("H1.HIF", ImportItemRole.COMPANION))
        val jpegOnly = candidate("J1", image("J1.JPG"))
        val heifOnly = candidate("H2", image("H2.HEIC"))

        val items = plan(listOf(pair("DSC1"), heifPair, jpegOnly, heifOnly), includeRaster = false)

        assertEquals(listOf("DSC1.ARW", "DSC1.xmp", "H1.ARW"), items.map { it.targetName })
    }

    @Test
    fun `turning RAW off makes the companion the primary and drops RAW-only photos`() {
        val rawOnly = candidate("R1", image("R1.ARW"))
        val items = plan(listOf(pair("DSC1"), rawOnly), includeRaw = false)

        assertEquals(listOf("DSC1.JPG", "DSC1.xmp"), items.map { it.targetName })
        assertEquals(ImportItemRole.PRIMARY, items.first().role)
    }

    @Test
    fun `a darktable sidecar leaves with the file it describes`() {
        val photo = candidate(
            "DSC1",
            image("DSC1.ARW"),
            image("DSC1.JPG", ImportItemRole.COMPANION),
            sidecar("DSC1.ARW.xmp", ImportItemRole.DARKTABLE_SIDECAR),
            sidecar("DSC1.JPG.xmp", ImportItemRole.DARKTABLE_SIDECAR),
        )
        val items = plan(listOf(photo), includeRaw = false)
        assertEquals(listOf("DSC1.JPG", "DSC1.JPG.xmp"), items.map { it.targetName })
    }

    @Test
    fun `same name and same content is skipped as a duplicate`() {
        val items = plan(
            listOf(candidate("DSC1", image("DSC1.ARW"))),
            existing = mapOf("2026/dsc1.arw" to existingFile()),
        )
        val item = items.single()
        assertTrue(item.skippedDuplicate)
        assertEquals("DSC1.ARW", item.targetName)
        assertEquals("lib://x", item.existingTargetUri)
    }

    @Test
    fun `same name with another size renames the whole group`() {
        val items = plan(listOf(pair("DSC1")), existing = mapOf("2026/dsc1.arw" to existingFile(size = 99L)))

        assertEquals(listOf("DSC1-1.ARW", "DSC1-1.JPG", "DSC1-1.xmp"), items.map { it.targetName })
        assertFalse(items.any { it.skippedDuplicate })
    }

    @Test
    fun `same name and size with another hash renames the whole group`() {
        val items = plan(listOf(pair("DSC1")), existing = mapOf("2026/dsc1.jpg" to existingFile(head = "other")))
        assertEquals(listOf("DSC1-1.ARW", "DSC1-1.JPG", "DSC1-1.xmp"), items.map { it.targetName })
    }

    @Test
    fun `an unreadable hash counts as different content, so nothing is lost`() {
        val items = plan(
            listOf(candidate("DSC1", image("DSC1.ARW"))),
            existing = mapOf("2026/dsc1.arw" to existingFile()),
            sourceHead = { null },
        )
        assertEquals("DSC1-1.ARW", items.single().targetName)
    }

    @Test
    fun `suffixes count up past names that are taken`() {
        val items = plan(
            listOf(candidate("DSC1", image("DSC1.ARW"))),
            existing = mapOf(
                "2026/dsc1.arw" to existingFile(size = 1L),
                "2026/dsc1-1.arw" to existingFile(size = 2L),
            ),
        )
        assertEquals("DSC1-2.ARW", items.single().targetName)
    }

    @Test
    fun `a suffix taken by this same content is recognised on reimport`() {
        val items = plan(
            listOf(candidate("DSC1", image("DSC1.ARW"))),
            existing = mapOf(
                "2026/dsc1.arw" to existingFile(size = 1L),
                "2026/dsc1-1.arw" to existingFile(),
            ),
        )
        assertEquals("DSC1-1.ARW", items.single().targetName)
        assertTrue(items.single().skippedDuplicate)
    }

    @Test
    fun `a duplicate RAW with a new companion stays paired`() {
        val items = plan(
            listOf(candidate("DSC1", image("DSC1.ARW"), image("DSC1.JPG", ImportItemRole.COMPANION))),
            existing = mapOf("2026/dsc1.arw" to existingFile()),
        )
        assertEquals(listOf("DSC1.ARW", "DSC1.JPG"), items.map { it.targetName })
        assertEquals(listOf(true, false), items.map { it.skippedDuplicate })
    }

    @Test
    fun `sidecars follow the suffix in both styles`() {
        val photo = candidate(
            "DSC1",
            image("DSC1.ARW"),
            sidecar("DSC1.xmp"),
            sidecar("DSC1.ARW.xmp", ImportItemRole.DARKTABLE_SIDECAR),
        )
        val items = plan(listOf(photo), existing = mapOf("2026/dsc1.arw" to existingFile(size = 1L)))
        assertEquals(listOf("DSC1-1.ARW", "DSC1-1.xmp", "DSC1-1.ARW.xmp"), items.map { it.targetName })
    }

    @Test
    fun `an existing library sidecar is kept, not replaced or treated as a conflict`() {
        val items = plan(listOf(pair("DSC1")), existing = mapOf("2026/dsc1.xmp" to existingFile(size = 1L)))

        assertEquals(listOf("DSC1.ARW", "DSC1.JPG", "DSC1.xmp"), items.map { it.targetName })
        assertEquals(listOf(false, false, true), items.map { it.skippedDuplicate })
    }

    @Test
    fun `two cards folders with the same names never collide in one job`() {
        val first = candidate("DSC1", image("DSC1.ARW"), id = "a", stemGroup = "100/dsc1")
        val second = candidate("DSC1", image("DSC1.ARW"), id = "b", stemGroup = "101/dsc1")

        val items = plan(listOf(first, second))

        assertEquals(listOf("DSC1.ARW", "DSC1-1.ARW"), items.map { it.targetName })
    }

    @Test
    fun `names compare without regard to case`() {
        val first = candidate("DSC1", image("DSC1.ARW"), id = "a", stemGroup = "100/dsc1")
        val second = candidate("dsc1", image("dsc1.arw"), id = "b", stemGroup = "101/dsc1")
        assertEquals(listOf("DSC1.ARW", "dsc1-1.arw"), plan(listOf(first, second)).map { it.targetName })
    }

    @Test
    fun `assets sharing a stem take one suffix and go to one folder`() {
        val raw = candidate("DSC1", image("DSC1.ARW"), id = "raw", stemGroup = "g", date = 1L)
        val heif = candidate("DSC1", image("DSC1.HIF"), id = "heif", stemGroup = "g", date = 2L)

        val items = plan(
            listOf(raw, heif),
            existing = mapOf("day-1/dsc1.arw" to existingFile(size = 1L)),
            template = { "day-$it" },
        )

        assertEquals(listOf("DSC1-1.ARW", "DSC1-1.HIF"), items.map { it.targetName })
        assertEquals(setOf("day-1"), items.map { it.targetRelativeDir }.toSet())
    }

    @Test
    fun `only later assets of a stem carry a discriminator`() {
        val raw = candidate("DSC1", image("DSC1.ARW"), id = "raw", stemGroup = "g")
        val heif = candidate("DSC1", image("DSC1.HIF"), id = "heif", stemGroup = "g")

        val items = plan(listOf(raw, heif))

        assertNull(items[0].assetDiscriminator)
        assertEquals("hif", items[1].assetDiscriminator)
    }

    @Test
    fun `EXIF travels on the primary only`() {
        val photo = pair("DSC1").copy(metadataJson = "{}")
        val items = plan(listOf(photo))
        assertEquals(listOf("{}", null, null), items.map { it.metadataJson })
    }

    @Test
    fun `nothing selected plans nothing`() {
        assertTrue(plan(listOf(pair("DSC1")), selected = emptySet()).isEmpty())
    }
}
