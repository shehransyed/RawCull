package dev.shehran.rawcull.device

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assume.assumeTrue

/**
 * R8 D10: the tester's real camera files, read only. Tests list the folder themselves and derive
 * counts from it. Nothing here writes to, renames or deletes anything in [dir].
 */
object Samples {
    const val DEFAULT_DIR = "/sdcard/Download/Quick Share/Sample Import"

    data class Sample(val name: String, val size: Long) {
        val stem: String get() = name.substringBeforeLast('.')
    }

    /** Instrumentation argument `samples` overrides the default folder. */
    val dir: String
        get() = InstrumentationRegistry.getArguments().getString("samples") ?: DEFAULT_DIR

    /** Skips the calling test when the folder is missing or empty. */
    fun assumeAvailable(): List<Sample> {
        val files = list()
        assumeTrue("No samples in $dir", files.isNotEmpty())
        return files
    }

    /** Files directly in [dir], sorted by name. */
    fun list(): List<Sample> =
        Shell.text("cd ${Shell.quote(dir)} 2>/dev/null && for f in *; do [ -f \"\$f\" ] && stat -c '%s|%n' \"\$f\"; done")
            .lineSequence()
            .mapNotNull { line ->
                val size = line.substringBefore('|').toLongOrNull() ?: return@mapNotNull null
                Sample(line.substringAfter('|'), size)
            }
            .sortedBy { it.name }
            .toList()

    /** Files sharing a stem, the way the Library pairs RAW + JPEG; sorted by stem. */
    fun groups(): List<List<Sample>> =
        assumeAvailable().groupBy { it.stem.lowercase() }.toSortedMap().values.toList()

    /** Streams [sample] into [target] in the app's own storage. */
    fun copy(sample: Sample, target: File) {
        target.parentFile?.mkdirs()
        Shell.run("cat ${Shell.quote("$dir/${sample.name}")}") { input -> target.outputStream().use { input.copyTo(it) } }
        check(target.length() == sample.size) { "Copied ${target.length()} of ${sample.size} bytes of ${sample.name}" }
    }

    /** Copies [count] whole groups into [targetDir]; returns them. */
    fun copyGroups(count: Int, targetDir: File): List<List<Sample>> {
        val chosen = groups().take(count)
        assumeTrue("Need $count sample groups, found ${chosen.size}", chosen.size == count)
        chosen.flatten().forEach { copy(it, File(targetDir, it.name)) }
        return chosen
    }
}
