package dev.shehran.rawcull.qa

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import dev.shehran.rawcull.RawCullApp
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * qa only (R8 D16), driven by `scripts/update-check.sh`:
 * `am start -n dev.shehran.rawcull.qa/dev.shehran.rawcull.qa.QaStateActivity --es cmd seed|dump`.
 * `seed` resets the app and builds a small library through the fixture provider (ratings and a pick
 * come from sidecars, a setting is changed); `dump` describes it. Both write [STATE_FILE]: first
 * `status=done` or `status=failed`, then the state. Later rounds' qa builds read the same library,
 * so keep this format stable; lines starting with `#` may change between versions.
 */
class QaStateActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cmd = intent.getStringExtra("cmd") ?: "dump"
        val app = application as RawCullApp
        val out = File(filesDir, STATE_FILE)
        out.delete()
        app.container.applicationScope.launch {
            val text = runCatching {
                if (cmd == "seed") seed(app)
                "status=done\n" + dump(app)
            }.getOrElse { "status=failed\n# $it\n" }
            out.writeText(text)
            Log.i(TAG, "$cmd: ${text.lineSequence().first()}")
        }
        finish()
    }

    private suspend fun seed(app: RawCullApp) {
        val container = app.container
        QaReset.resetAll(app)
        val dir = FixtureDocumentsProvider.dir(app, TREE)
        SEED.forEachIndexed { index, (stem, rating, pick) ->
            writeJpeg(File(dir, "$stem.jpg"), index)
            File(dir, "$stem.xmp").writeText(sidecar(rating, pick))
        }
        val treeUri = FixtureDocumentsProvider.treeUri(app, TREE).toString()
        checkNotNull(container.locationRepository.add(treeUri)) { "Location not added" }
        container.settingsRepository.setPreviewCacheLimitMb(SEED_CACHE_LIMIT_MB)
        container.scanCoordinator.start(treeUri)
        withTimeout(SEED_TIMEOUT_MS) {
            while (true) {
                val rows = container.photoDao.getByRoot(treeUri)
                val ratings = rows.associate { it.fileName.substringBeforeLast('.') to it.rating }
                if (SEED.all { ratings[it.first] == it.second }) break
                delay(POLL_MS)
            }
        }
    }

    private suspend fun dump(app: RawCullApp): String = buildString {
        val container = app.container
        container.startup.await()
        appendLine("# version=${packageManager.getPackageInfo(packageName, 0).versionName}")
        appendLine("# db=${container.database.openHelper.readableDatabase.version}")
        val locations = container.locationRepository.getAll().sortedBy { it.treeUri }
        appendLine("locations=${locations.size}")
        locations.forEach { location ->
            appendLine("location ${location.displayName} ${location.treeUri}")
            container.photoDao.getByRoot(location.treeUri).sortedBy { it.fileName }.forEach {
                appendLine("photo ${it.fileName} rating=${it.rating} status=${it.cullStatus}")
            }
        }
        appendLine("setting preview_cache_limit_mb=${container.settingsRepository.previewCacheLimitMb.first()}")
    }

    private fun writeJpeg(file: File, index: Int) {
        val bitmap = Bitmap.createBitmap(JPEG_SIZE, JPEG_SIZE, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(40 * (index + 1), 90, 160))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        bitmap.recycle()
    }

    private fun sidecar(rating: Int, pick: Int): String =
        """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">""" +
            """<rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:rawcull="http://ns.rawcull.dev/xmp/1.0/" """ +
            """xmp:Rating="$rating" rawcull:Rating="$rating" rawcull:Pick="$pick"/></rdf:RDF></x:xmpmeta>"""

    companion object {
        const val STATE_FILE = "qa-state.txt"
        private const val TAG = "RawCull.QaState"
        private const val TREE = "update"
        private const val SEED_CACHE_LIMIT_MB = 777
        private const val SEED_TIMEOUT_MS = 120_000L
        private const val POLL_MS = 250L
        private const val JPEG_SIZE = 64
        private const val JPEG_QUALITY = 90

        /** Stem, rating, pick (1, 0 or -1). */
        private val SEED = listOf(Triple("UPD_001", 5, 1), Triple("UPD_002", 3, 0), Triple("UPD_003", 1, -1))

        fun stateFile(context: Context): File = File(context.filesDir, STATE_FILE)
    }
}
