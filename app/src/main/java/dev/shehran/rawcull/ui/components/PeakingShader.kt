package dev.shehran.rawcull.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import dev.shehran.rawcull.library.PeakingKernel

/**
 * D8's GPU path (API 33+): [PeakingKernel] as an AGSL shader. The photo is the child shader,
 * placed where the loupe draws it, so neighbours are sampled one screen pixel apart and the mask
 * is at display scale; hardware bitmaps work as they are. Same constants as the kernel.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class PeakingShader private constructor() {
    private val shader = RuntimeShader(AGSL)
    private val paint = Paint().apply { shader = this@PeakingShader.shader }
    private val area = RectF()

    /**
     * Draws the marks for [bitmap] (drawn with [matrix]) over its own on-screen rectangle within
     * [bounds], leaving out [exclude] (where the tile covers it).
     */
    fun draw(canvas: Canvas, bitmap: Bitmap, matrix: Matrix, bounds: RectF, color: Int, threshold: Float, exclude: RectF? = null) {
        area.set(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        matrix.mapRect(area)
        if (!area.intersect(bounds)) return
        val image = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(matrix)
            filterMode = BitmapShader.FILTER_MODE_LINEAR
        }
        shader.setInputShader("image", image)
        shader.setColorUniform("color", color)
        shader.setFloatUniform("threshold", threshold)
        canvas.save()
        if (exclude != null) canvas.clipOutRect(exclude)
        canvas.drawRect(area, paint)
        canvas.restore()
    }

    companion object {
        @Volatile private var instance: PeakingShader? = null
        @Volatile private var failed = false

        /** The shared shader, or null if this device can't compile it (then the CPU path runs). */
        fun get(): PeakingShader? {
            if (failed) return null
            instance?.let { return it }
            return synchronized(this) {
                instance ?: runCatching { PeakingShader() }
                    .onFailure {
                        failed = true
                        Log.w("PeakingShader", "AGSL peaking unavailable, using the CPU path", it)
                    }
                    .getOrNull()
                    ?.also { instance = it }
            }
        }
    }
}

/** The shader's source; the numbers are [PeakingKernel]'s. */
private val AGSL = """
    uniform shader image;
    layout(color) uniform half4 color;
    uniform float threshold;

    float luma(float2 p) {
        return dot(float3(image.eval(p).rgb), float3(0.2126, 0.7152, 0.0722));
    }

    float gradient(float2 p, float d) {
        float n = luma(p + float2(0.0, -d));
        float s = luma(p + float2(0.0, d));
        float w = luma(p + float2(-d, 0.0));
        float e = luma(p + float2(d, 0.0));
        float nw = luma(p + float2(-d, -d));
        float ne = luma(p + float2(d, -d));
        float sw = luma(p + float2(-d, d));
        float se = luma(p + float2(d, d));
        return (length(float2(e - w, s - n)) + length(float2(se - nw, sw - ne))) * 0.5;
    }

    float focus(float2 p) {
        float c = luma(p);
        float n = luma(p + float2(0.0, -1.0));
        float s = luma(p + float2(0.0, 1.0));
        float w = luma(p + float2(-1.0, 0.0));
        float e = luma(p + float2(1.0, 0.0));
        float nw = luma(p + float2(-1.0, -1.0));
        float ne = luma(p + float2(1.0, -1.0));
        float sw = luma(p + float2(-1.0, 1.0));
        float se = luma(p + float2(1.0, 1.0));
        float lo = min(c, min(min(w, e), min(min(n, s), min(min(nw, ne), min(sw, se)))));
        float hi = max(c, max(max(w, e), max(max(n, s), max(max(nw, ne), max(sw, se)))));
        if (hi - lo < ${PeakingKernel.NOISE_GATE}) return 0.0;
        float close = (length(float2(e - w, s - n)) + length(float2(se - nw, sw - ne))) * 0.5;
        float far = gradient(p, 2.0);
        return max(close - ${PeakingKernel.FAR_WEIGHT} * max(far - ${PeakingKernel.FAR_FLOOR}, 0.0), 0.0);
    }

    half4 main(float2 p) {
        float v = focus(p);
        v = max(v, focus(p + float2(-1.0, 0.0)));
        v = max(v, focus(p + float2(1.0, 0.0)));
        v = max(v, focus(p + float2(0.0, -1.0)));
        v = max(v, focus(p + float2(0.0, 1.0)));
        return v > threshold ? color : half4(0.0);
    }
""".trimIndent()
