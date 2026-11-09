package dev.shehran.rawcull.device.benchmark

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.shehran.rawcull.MainActivity
import dev.shehran.rawcull.RawCullApp
import dev.shehran.rawcull.data.preview.BenchmarkSample
import dev.shehran.rawcull.data.preview.BenchmarkStage
import dev.shehran.rawcull.data.preview.BenchmarkTable
import dev.shehran.rawcull.data.preview.PreviewBenchmark
import dev.shehran.rawcull.data.preview.PreviewKeys
import dev.shehran.rawcull.device.DeviceOut
import dev.shehran.rawcull.device.Samples
import dev.shehran.rawcull.device.deviceRules
import dev.shehran.rawcull.device.robots.LibraryRobot
import dev.shehran.rawcull.qa.FixtureDocumentsProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * R8.7 (D15): `PreviewBenchmark` over the real samples in the qa build. The p50 and p90 of every
 * stage go to `device-out/bench/preview.json` (`<stage>_p50`, milliseconds); the runner puts them
 * in the report beside the last run's and flags anything over 2× slower. Debuggable build, so
 * read trends, not absolutes: the test only asserts that every stage produced numbers.
 */
@RunWith(AndroidJUnit4::class)
class PreviewBenchmarkDeviceTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = deviceRules(compose)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as RawCullApp).container
    private val library by lazy { LibraryRobot(compose) }

    @Test
    fun benchmarkSamples() {
        val dir = FixtureDocumentsProvider.dir(context, "bench")
        val groups = Samples.copyGroups(3, dir)
        library.openTab().addLocation(FixtureDocumentsProvider.treeUri(context, "bench")).awaitGroups(groups.size, timeoutMs = 120_000)
        library.await(120_000) { library.state().photos.size == groups.size && library.state().photos.all { it.metadataLoaded } }

        val photos = library.state().photos.map {
            PreviewBenchmark.Photo(PreviewKeys.source(it), if (it.hasCompanion) "${it.format.name}+${it.companionFormat?.name ?: "JPG"}" else it.format.name)
        }
        container.previewWarmup.cancel()
        val samples = runBlocking { PreviewBenchmark(container.previewRepository).run(photos, FIT_WIDTH, FIT_HEIGHT) }
        println(BenchmarkTable.format(samples, "device benchmark, ${photos.size} photos"))

        for (stage in BenchmarkStage.entries) {
            assertTrue("no numbers for ${stage.label}", samples.any { it.stage == stage })
        }
        File(DeviceOut.bench(), "preview.json").writeText(json(samples))
    }

    private fun json(samples: List<BenchmarkSample>): String = BenchmarkStage.entries.flatMap { stage ->
        val totals = samples.filter { it.stage == stage }.map { it.timing.totalMs }
        listOf(50, 90).map { p -> "\"${stage.name.lowercase()}_p$p\": ${"%.1f".format(java.util.Locale.ROOT, BenchmarkTable.percentile(totals, p))}" }
    }.joinToString(", ", "{", "}")

    private companion object {
        const val FIT_WIDTH = 1600
        const val FIT_HEIGHT = 1000
    }
}
