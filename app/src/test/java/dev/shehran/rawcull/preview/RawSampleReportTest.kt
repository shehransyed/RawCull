package dev.shehran.rawcull.preview

import dev.shehran.rawcull.data.model.PhotoFormat
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import javax.imageio.ImageIO

private const val MIN_LONG_EDGE = 1000

/** Opt-in: `-PrawSamples=<dir>`. Real RAWs are too big for the repo, so they live outside it. */
class RawSampleReportTest {
    private class FileSource(private val file: RandomAccessFile) : ByteSource {
        override val size: Long = file.length()
        override val seekable: Boolean = true

        override fun read(offset: Long, length: Int): ByteArray {
            if (offset < 0 || offset >= size) return ByteArray(0)
            val out = ByteArray(minOf(length.toLong(), size - offset).toInt())
            file.seek(offset)
            file.readFully(out)
            return out
        }
    }

    @Test
    fun `every sample yields a preview`() {
        val dir = System.getProperty("rawSamples")
        assumeTrue("Set -PrawSamples=<dir> to run", !dir.isNullOrBlank())
        val files = File(dir!!).walkTopDown().filter { it.isFile }
            .mapNotNull { file -> PhotoFormat.fromFileNameOrNull(file.name)?.let { file to it } }.toList()
        assumeTrue("No photos in $dir", files.isNotEmpty())

        val failures = mutableListOf<String>()
        val report = StringBuilder()
        report.appendLine("file | format | orientation | parse ms | read KB | candidates (w×h, KB, @offset, origin) | thumb 512 | fit 2560×1600 | full")
        report.appendLine("---|---|---|---|---|---|---|---|---")
        files.forEach { (file, format) ->
            RandomAccessFile(file, "r").use { handle ->
                val counted = CountingSource(FileSource(handle))
                val source = BlockCachedSource(counted)
                val started = System.nanoTime()
                source.preload(0, HEADER_SLICE_BYTES)
                val scan = PreviewScanner.forFormat(format).scan(source)
                val ms = (System.nanoTime() - started) / 1_000_000.0

                val pool = PreviewChooser.pool(scan, null)
                val thumb = PreviewChooser.pick(pool, PreviewRequest.Thumbnail(512))
                val fit = PreviewChooser.pick(pool, PreviewRequest.Fit(2560, 1600))
                val full = PreviewChooser.pick(pool, PreviewRequest.FullResolution)
                val listed = scan.candidates.joinToString("<br>") {
                    "${it.width}×${it.height}, ${it.length / 1024} KB, @${it.offset}, ${it.origin}"
                }
                report.appendLine(
                    "${file.name} | $format | ${scan.orientation} | ${"%.1f".format(ms)} | ${counted.bytesRead / 1024} | $listed | " +
                        "${label(thumb)} | ${label(fit)} | ${label(full)}",
                )

                // D2: a DNG with no JPEG at all, as phones write, is ImageDecoder's last resort.
                val decodedInstead = format == PhotoFormat.DNG && scan.candidates.isEmpty()
                if (format.isRaw && !decodedInstead && scan.candidates.none { it.longEdge >= MIN_LONG_EDGE }) {
                    failures += "${file.name}: nothing of $MIN_LONG_EDGE px or more"
                }
                scan.candidates.forEach { candidate ->
                    val problem = decodeProblem(source, candidate)
                    if (problem != null) failures += "${file.name} @${candidate.offset}: $problem"
                }
            }
        }
        println(report)
        File(System.getProperty("rawSamplesReport") ?: "build/reports/raw-samples.md").apply {
            parentFile?.mkdirs()
            writeText(report.toString())
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun label(pick: PooledCandidate?): String = pick?.candidate?.let { "${it.width}×${it.height}" } ?: "none"

    /** Checks the bytes against a second JPEG reader, so a wrong range can't pass as a good one. */
    private fun decodeProblem(source: ByteSource, candidate: PreviewCandidate): String? {
        val bytes = source.read(candidate.offset, candidate.length.toInt())
        val stream = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
        val reader = ImageIO.getImageReadersByFormatName("jpeg").next()
        return try {
            reader.input = stream
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            if (width != candidate.width || height != candidate.height) "reader says $width×$height" else null
        } finally {
            reader.dispose()
        }
    }
}
