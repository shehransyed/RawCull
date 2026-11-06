package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.net.Uri
import com.example.data.model.ColorLabel
import com.example.data.model.CullStatus
import com.example.data.model.PairMode
import com.example.data.model.RawFormat
import com.example.data.model.RawPhotoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object SamplePhotosGenerator {

    suspend fun createSampleShoot(context: Context): List<RawPhotoItem> = withContext(Dispatchers.IO) {
        val sampleDir = File(context.filesDir, "sample_raw_shoot")
        if (!sampleDir.exists()) sampleDir.mkdirs()

        val sampleConfigs = listOf(
            SampleDef(
                fileName = "DSC04891.ARW",
                format = RawFormat.ARW,
                cameraMake = "SONY",
                cameraModel = "ILCE-7M4 (Sony A7 IV)",
                lensModel = "FE 24-70mm F2.8 GM II",
                shutter = "1/1600s",
                aperture = "f/2.8",
                iso = "200",
                focalLength = "50 mm",
                exposureBias = "0 EV",
                sceneType = SceneType.GOLDEN_COAST,
                sizeBytes = 35_482_112L,
                initialStatus = CullStatus.PICK,
                initialRating = 5,
                initialLabel = ColorLabel.GREEN,
                tags = listOf("Landscape", "Golden Hour", "Sony A7IV")
            ),
            SampleDef(
                fileName = "DSC04892.ARW",
                format = RawFormat.ARW,
                cameraMake = "SONY",
                cameraModel = "ILCE-7M4 (Sony A7 IV)",
                lensModel = "FE 24-70mm F2.8 GM II",
                shutter = "1/1600s",
                aperture = "f/2.8",
                iso = "200",
                focalLength = "50 mm",
                exposureBias = "+0.3 EV",
                sceneType = SceneType.GOLDEN_COAST_BURST2,
                sizeBytes = 35_214_500L,
                initialStatus = CullStatus.UNFLAGGED,
                initialRating = 4,
                initialLabel = ColorLabel.YELLOW,
                tags = listOf("Landscape", "Golden Hour")
            ),
            SampleDef(
                fileName = "DSC04893.ARW",
                format = RawFormat.ARW,
                cameraMake = "SONY",
                cameraModel = "ILCE-7M4 (Sony A7 IV)",
                lensModel = "FE 24-70mm F2.8 GM II",
                shutter = "1/200s",
                aperture = "f/2.8",
                iso = "800",
                focalLength = "70 mm",
                exposureBias = "-0.7 EV",
                sceneType = SceneType.BLURRED_MOTION,
                sizeBytes = 34_891_200L,
                initialStatus = CullStatus.REJECT,
                initialRating = 1,
                initialLabel = ColorLabel.RED,
                tags = listOf("Motion Blur", "Reject")
            ),
            SampleDef(
                fileName = "L1002341.DNG",
                format = RawFormat.DNG,
                cameraMake = "LEICA CAMERA AG",
                cameraModel = "LEICA M11",
                lensModel = "APO-Summicron-M 50mm f/2 ASPH",
                shutter = "1/2000s",
                aperture = "f/2.0",
                iso = "64",
                focalLength = "50 mm",
                exposureBias = "0 EV",
                sceneType = SceneType.ALPINE_PEAKS,
                sizeBytes = 72_412_890L,
                initialStatus = CullStatus.PICK,
                initialRating = 5,
                initialLabel = ColorLabel.BLUE,
                tags = listOf("Mountains", "High Altitude", "Leica M11")
            ),
            SampleDef(
                fileName = "L1002342.DNG",
                format = RawFormat.DNG,
                cameraMake = "LEICA CAMERA AG",
                cameraModel = "LEICA M11",
                lensModel = "APO-Summicron-M 50mm f/2 ASPH",
                shutter = "1/1000s",
                aperture = "f/4.0",
                iso = "100",
                focalLength = "50 mm",
                exposureBias = "+0.3 EV",
                sceneType = SceneType.FOREST_MIST,
                sizeBytes = 69_812_000L,
                initialStatus = CullStatus.UNFLAGGED,
                initialRating = 3,
                initialLabel = ColorLabel.NONE,
                tags = listOf("Forest", "Atmospheric")
            ),
            SampleDef(
                fileName = "DJI_0428.DNG",
                format = RawFormat.DNG,
                cameraMake = "DJI",
                cameraModel = "Mavic 3 Pro",
                lensModel = "Hasselblad 24mm f/2.8",
                shutter = "1/800s",
                aperture = "f/4.5",
                iso = "100",
                focalLength = "24 mm",
                exposureBias = "0 EV",
                sceneType = SceneType.AERIAL_ARCHIPELAGO,
                sizeBytes = 42_190_000L,
                initialStatus = CullStatus.PICK,
                initialRating = 4,
                initialLabel = ColorLabel.PURPLE,
                tags = listOf("Aerial", "Coastal", "Drone")
            ),
            SampleDef(
                fileName = "DSC04905.ARW",
                format = RawFormat.ARW,
                cameraMake = "SONY",
                cameraModel = "ILCE-7M4 (Sony A7 IV)",
                lensModel = "FE 85mm F1.4 GM",
                shutter = "1/2500s",
                aperture = "f/1.4",
                iso = "100",
                focalLength = "85 mm",
                exposureBias = "+0.3 EV",
                sceneType = SceneType.STUDIO_PORTRAIT,
                sizeBytes = 36_110_400L,
                initialStatus = CullStatus.PICK,
                initialRating = 5,
                initialLabel = ColorLabel.GREEN,
                tags = listOf("Portrait", "Bokeh", "Select")
            ),
            SampleDef(
                fileName = "DSC04906.ARW",
                format = RawFormat.ARW,
                cameraMake = "SONY",
                cameraModel = "ILCE-7M4 (Sony A7 IV)",
                lensModel = "FE 85mm F1.4 GM",
                shutter = "1/2500s",
                aperture = "f/1.4",
                iso = "100",
                focalLength = "85 mm",
                exposureBias = "+0.3 EV",
                sceneType = SceneType.STUDIO_PORTRAIT_BLINK,
                sizeBytes = 35_980_100L,
                initialStatus = CullStatus.REJECT,
                initialRating = 2,
                initialLabel = ColorLabel.RED,
                tags = listOf("Portrait", "Blinked")
            )
        )

        val resultItems = mutableListOf<RawPhotoItem>()
        val baseTime = System.currentTimeMillis() - (sampleConfigs.size * 180_000L)

        sampleConfigs.forEachIndexed { index, def ->
            val file = File(sampleDir, def.fileName)
            // Generate RAW file
            if (!file.exists() || file.length() < 1000) {
                val bitmap = renderSceneBitmap(def.sceneType)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
            }

            // Create companion JPEG for the first 4 shots to demonstrate RAW+JPEG pairing
            var companionJpegUri: String? = null
            var companionJpegName: String? = null
            var companionJpegSize: Long? = null
            var pairMode = PairMode.RAW_ONLY

            if (index < 4) {
                val stem = def.fileName.substringBeforeLast('.')
                val jpegFile = File(sampleDir, "$stem.JPG")
                if (!jpegFile.exists() || jpegFile.length() < 1000) {
                    val bitmap = renderSceneBitmap(def.sceneType)
                    FileOutputStream(jpegFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    }
                }
                companionJpegUri = Uri.fromFile(jpegFile).toString()
                companionJpegName = "$stem.JPG"
                companionJpegSize = 8_420_000L
                pairMode = PairMode.RAW_AND_JPEG
            }

            // Create initial .xmp sidecar file
            val xmpFile = File(sampleDir, "${def.fileName.substringBeforeLast('.')}.xmp")
            val item = RawPhotoItem(
                id = "sample_${def.fileName}",
                fileName = def.fileName,
                fileUri = Uri.fromFile(file).toString(),
                folderUri = Uri.fromFile(sampleDir).toString(),
                format = def.format,
                fileSizeBytes = def.sizeBytes,
                dateTaken = baseTime + (index * 180_000L),
                cullStatus = def.initialStatus,
                rating = def.initialRating,
                colorLabel = def.initialLabel,
                tags = def.tags,
                width = 6000,
                height = 4000,
                cameraMake = def.cameraMake,
                cameraModel = def.cameraModel,
                lensModel = def.lensModel,
                shutterSpeed = def.shutter,
                aperture = def.aperture,
                iso = def.iso,
                focalLength = def.focalLength,
                exposureBias = def.exposureBias,
                isSample = true,
                jpegFileName = companionJpegName,
                jpegFileUri = companionJpegUri,
                jpegFileSizeBytes = companionJpegSize,
                xmpFileUri = Uri.fromFile(xmpFile).toString(),
                pairMode = pairMode
            )

            // Write matching XMP sidecar on disk
            runCatching {
                val xmpXml = XmpSidecarGenerator.generateXmp(item)
                FileOutputStream(xmpFile).use { out ->
                    out.write(xmpXml.toByteArray(Charsets.UTF_8))
                }
            }

            resultItems.add(item)
        }

        resultItems
    }

    private fun renderSceneBitmap(scene: SceneType): Bitmap {
        val width = 1600
        val height = 1067
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        when (scene) {
            SceneType.GOLDEN_COAST -> {
                // Sunset sky gradient
                paint.shader = LinearGradient(
                    0f, 0f, 0f, height * 0.65f,
                    intArrayOf(Color.parseColor("#1A1C2E"), Color.parseColor("#7A3E48"), Color.parseColor("#E65C40"), Color.parseColor("#FFB03B")),
                    floatArrayOf(0f, 0.4f, 0.75f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, width.toFloat(), height * 0.65f, paint)

                // Sun glow
                paint.shader = RadialGradient(
                    width * 0.65f, height * 0.52f, 320f,
                    intArrayOf(Color.parseColor("#FFF7D1"), Color.parseColor("#FFA03B"), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.35f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawCircle(width * 0.65f, height * 0.52f, 320f, paint)

                // Ocean water with reflections
                paint.shader = LinearGradient(
                    0f, height * 0.65f, 0f, height.toFloat(),
                    intArrayOf(Color.parseColor("#E67E22"), Color.parseColor("#2C3E50"), Color.parseColor("#0F172A")),
                    floatArrayOf(0f, 0.3f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, height * 0.65f, width.toFloat(), height.toFloat(), paint)

                // Coastal sea stack rocks silhouette
                paint.shader = null
                paint.color = Color.parseColor("#0F172A")
                val rockPath = Path().apply {
                    moveTo(width * 0.15f, height.toFloat())
                    lineTo(width * 0.22f, height * 0.45f)
                    lineTo(width * 0.28f, height * 0.42f)
                    lineTo(width * 0.36f, height * 0.58f)
                    lineTo(width * 0.42f, height.toFloat())
                    close()
                }
                canvas.drawPath(rockPath, paint)
            }
            SceneType.GOLDEN_COAST_BURST2 -> {
                // Wave crash variant
                renderSceneBitmap(SceneType.GOLDEN_COAST).let { base ->
                    val tmpCanvas = Canvas(base)
                    val foamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#CCFFFFFF")
                    }
                    tmpCanvas.drawCircle(width * 0.25f, height * 0.62f, 40f, foamPaint)
                    return base
                }
            }
            SceneType.BLURRED_MOTION -> {
                // Dark motion blur test
                paint.shader = LinearGradient(
                    0f, 0f, width.toFloat(), height.toFloat(),
                    intArrayOf(Color.parseColor("#374151"), Color.parseColor("#1F2937"), Color.parseColor("#111827")),
                    null,
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
                paint.shader = null
                paint.color = Color.parseColor("#6B7280")
                paint.strokeWidth = 32f
                paint.style = Paint.Style.STROKE
                canvas.drawLine(100f, height * 0.5f, width - 100f, height * 0.5f, paint)
            }
            SceneType.ALPINE_PEAKS -> {
                // Dramatic snow peaks with clear turquoise sky
                paint.shader = LinearGradient(
                    0f, 0f, 0f, height * 0.55f,
                    intArrayOf(Color.parseColor("#0C4A6E"), Color.parseColor("#38BDF8")),
                    null,
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, width.toFloat(), height * 0.55f, paint)

                // Mountain peaks
                paint.shader = null
                paint.color = Color.parseColor("#F8FAFC")
                val peakPath = Path().apply {
                    moveTo(0f, height * 0.65f)
                    lineTo(width * 0.35f, height * 0.22f)
                    lineTo(width * 0.55f, height * 0.42f)
                    lineTo(width * 0.82f, height * 0.18f)
                    lineTo(width.toFloat(), height * 0.55f)
                    lineTo(width.toFloat(), height.toFloat())
                    lineTo(0f, height.toFloat())
                    close()
                }
                canvas.drawPath(peakPath, paint)

                // Mountain shadow side
                paint.color = Color.parseColor("#334155")
                val shadowPath = Path().apply {
                    moveTo(width * 0.35f, height * 0.22f)
                    lineTo(width * 0.55f, height * 0.42f)
                    lineTo(width * 0.45f, height.toFloat())
                    lineTo(width * 0.25f, height.toFloat())
                    close()
                }
                canvas.drawPath(shadowPath, paint)
            }
            SceneType.FOREST_MIST -> {
                // Misty deep forest greens
                paint.shader = LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    intArrayOf(Color.parseColor("#E2E8F0"), Color.parseColor("#64748B"), Color.parseColor("#14532D"), Color.parseColor("#052E16")),
                    floatArrayOf(0f, 0.35f, 0.7f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            }
            SceneType.AERIAL_ARCHIPELAGO -> {
                // Deep cyan tropical water and islands
                paint.shader = RadialGradient(
                    width * 0.5f, height * 0.5f, width * 0.6f,
                    intArrayOf(Color.parseColor("#06B6D4"), Color.parseColor("#0891B2"), Color.parseColor("#0E7490"), Color.parseColor("#155E75")),
                    floatArrayOf(0f, 0.4f, 0.75f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

                // Coral reef & atoll islands
                paint.shader = null
                paint.color = Color.parseColor("#FEF08A")
                canvas.drawCircle(width * 0.35f, height * 0.45f, 120f, paint)
                paint.color = Color.parseColor("#15803D")
                canvas.drawCircle(width * 0.35f, height * 0.45f, 85f, paint)

                paint.color = Color.parseColor("#FEF08A")
                canvas.drawCircle(width * 0.68f, height * 0.6f, 160f, paint)
                paint.color = Color.parseColor("#15803D")
                canvas.drawCircle(width * 0.68f, height * 0.6f, 120f, paint)
            }
            SceneType.STUDIO_PORTRAIT -> {
                // Clean studio backdrop with warm rim light
                paint.shader = RadialGradient(
                    width * 0.5f, height * 0.4f, width * 0.5f,
                    intArrayOf(Color.parseColor("#27272A"), Color.parseColor("#18181B"), Color.parseColor("#09090B")),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

                // Silhouette with warm golden rim
                paint.shader = null
                paint.color = Color.parseColor("#F59E0B")
                paint.strokeWidth = 14f
                paint.style = Paint.Style.STROKE
                canvas.drawCircle(width * 0.5f, height * 0.4f, 180f, paint)
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#18181B")
                canvas.drawCircle(width * 0.5f, height * 0.4f, 175f, paint)
            }
            SceneType.STUDIO_PORTRAIT_BLINK -> {
                val base = renderSceneBitmap(SceneType.STUDIO_PORTRAIT)
                return base
            }
        }

        return bitmap
    }

    private data class SampleDef(
        val fileName: String,
        val format: RawFormat,
        val cameraMake: String,
        val cameraModel: String,
        val lensModel: String,
        val shutter: String,
        val aperture: String,
        val iso: String,
        val focalLength: String,
        val exposureBias: String,
        val sceneType: SceneType,
        val sizeBytes: Long,
        val initialStatus: CullStatus,
        val initialRating: Int,
        val initialLabel: ColorLabel,
        val tags: List<String>
    )

    private enum class SceneType {
        GOLDEN_COAST,
        GOLDEN_COAST_BURST2,
        BLURRED_MOTION,
        ALPINE_PEAKS,
        FOREST_MIST,
        AERIAL_ARCHIPELAGO,
        STUDIO_PORTRAIT,
        STUDIO_PORTRAIT_BLINK
    }
}
