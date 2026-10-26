package bo.app.scanner.domain

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class FilterType(val label: String) {
    ORIGINAL("Original"),
    DOCUMENTO("Documento"),
    GRISES("Grises"),
    MAGIC("Magic Color"),
}

/**
 * Filtros sobre Bitmap. Las funciones *Blocking deben llamarse desde Dispatchers.Default.
 * El fondo del papel se estima a baja resolución (filtro de máximo + desenfoque) y se interpola
 * bilinealmente: elimina sombras sin mapas de bits intermedios a resolución completa.
 */
object ImageFilterEngine {

    private const val BG_SIDE = 160
    private const val STRIP = 64

    suspend fun apply(src: Bitmap, filter: FilterType): Bitmap =
        withContext(Dispatchers.Default) { applyBlocking(src, filter) }

    /** Devuelve [src] si el filtro es ORIGINAL; si no, un mapa de bits nuevo. */
    fun applyBlocking(src: Bitmap, filter: FilterType): Bitmap {
        if (filter == FilterType.ORIGINAL) return src
        val soft = if (src.config != Bitmap.Config.ARGB_8888) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val out = when (filter) {
            FilterType.GRISES -> grayscale(soft)
            FilterType.DOCUMENTO -> document(soft)
            FilterType.MAGIC -> magicColor(soft)
            FilterType.ORIGINAL -> soft
        }
        if (soft !== src && soft !== out) soft.recycle()
        return out
    }

    // ---------- Escala de grises: luminancia BT.601 ----------
    private fun grayscale(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val l = floatArrayOf(0.299f, 0.587f, 0.114f, 0f, 0f)
        val cm = ColorMatrix(l + l + l + floatArrayOf(0f, 0f, 0f, 1f, 0f))
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        return out
    }

    // ---------- Documento B&N: normalización de fondo + umbral de Otsu + contraste ----------
    private fun document(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val bg = Background(src, gray = true)
        val norm = ByteArray(w * h)
        val hist = IntArray(256)
        val row = IntArray(w * STRIP)

        var y0 = 0
        while (y0 < h) {
            val rows = min(STRIP, h - y0)
            src.getPixels(row, 0, w, 0, y0, w, rows)
            for (r in 0 until rows) {
                val y = y0 + r
                bg.prepareRow(y)
                for (x in 0 until w) {
                    val c = row[r * w + x]
                    val g = lum(c)
                    val b = bg.sample(0, x)
                    val n = (g * 255f / b).toInt().coerceIn(0, 255)
                    norm[y * w + x] = n.toByte()
                    hist[n]++
                }
            }
            y0 += rows
        }

        val t = otsu(hist).coerceIn(120, 235)
        val lo = percentile(hist, w.toLong() * h, 0.01).coerceAtMost(t - 1)
        val lut = IntArray(256) { n ->
            val v = if (n >= t) 255 else {
                val s = ((n - lo).toFloat() / (t - lo)).coerceIn(0f, 1f)
                (255f * s * s).roundToInt()
            }
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        y0 = 0
        while (y0 < h) {
            val rows = min(STRIP, h - y0)
            for (i in 0 until rows * w) row[i] = lut[norm[y0 * w + i].toInt() and 0xFF]
            out.setPixels(row, 0, w, 0, y0, w, rows)
            y0 += rows
        }
        return out
    }

    // ---------- Magic Color: división por fondo por canal, punto blanco y saturación ----------
    private fun magicColor(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val bg = Background(src, gray = false)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val row = IntArray(w * STRIP)

        var y0 = 0
        while (y0 < h) {
            val rows = min(STRIP, h - y0)
            src.getPixels(row, 0, w, 0, y0, w, rows)
            for (r in 0 until rows) {
                bg.prepareRow(y0 + r)
                for (x in 0 until w) {
                    val i = r * w + x
                    val c = row[i]
                    var rr = ((c shr 16) and 0xFF) * 255f / bg.sample(0, x)
                    var gg = ((c shr 8) and 0xFF) * 255f / bg.sample(1, x)
                    var bb = (c and 0xFF) * 255f / bg.sample(2, x)
                    rr = (rr - 25f) * 1.186f
                    gg = (gg - 25f) * 1.186f
                    bb = (bb - 25f) * 1.186f
                    val l = 0.299f * rr + 0.587f * gg + 0.114f * bb
                    rr = l + (rr - l) * 1.25f
                    gg = l + (gg - l) * 1.25f
                    bb = l + (bb - l) * 1.25f
                    row[i] = (0xFF shl 24) or
                        (rr.toInt().coerceIn(0, 255) shl 16) or
                        (gg.toInt().coerceIn(0, 255) shl 8) or
                        bb.toInt().coerceIn(0, 255)
                }
            }
            out.setPixels(row, 0, w, 0, y0, w, rows)
            y0 += rows
        }
        return out
    }

    private fun lum(c: Int): Float =
        0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)

    private fun otsu(hist: IntArray): Int {
        val total = hist.sum().toDouble()
        if (total == 0.0) return 200
        var sum = 0.0
        for (i in 0..255) sum += i * hist[i].toDouble()
        var sumB = 0.0
        var wB = 0.0
        var best = 0.0
        var t = 200
        for (i in 0..255) {
            wB += hist[i]
            if (wB == 0.0) continue
            val wF = total - wB
            if (wF == 0.0) break
            sumB += i * hist[i].toDouble()
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                t = i
            }
        }
        return t
    }

    private fun percentile(hist: IntArray, total: Long, p: Double): Int {
        val target = (total * p).toLong()
        var acc = 0L
        for (i in 0..255) {
            acc += hist[i]
            if (acc > target) return i
        }
        return 0
    }

    /** Fondo del papel a baja resolución con muestreo bilineal por fila. */
    private class Background(src: Bitmap, gray: Boolean) {
        private val sw: Int
        private val sh: Int
        private val ch: Array<FloatArray>
        private val w = src.width
        private val h = src.height
        private val x0 = IntArray(w)
        private val x1 = IntArray(w)
        private val fx = FloatArray(w)
        private val rowVals: Array<FloatArray>

        init {
            val s = BG_SIDE.toFloat() / max(w, h)
            sw = max(1, (w * s).roundToInt())
            sh = max(1, (h * s).roundToInt())
            val small = Bitmap.createScaledBitmap(src, sw, sh, true)
            val px = IntArray(sw * sh)
            small.getPixels(px, 0, sw, 0, 0, sw, sh)
            if (small !== src) small.recycle()
            val n = if (gray) 1 else 3
            ch = Array(n) { k ->
                val a = FloatArray(sw * sh) { i ->
                    val c = px[i]
                    if (gray) 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
                    else ((c shr (16 - 8 * k)) and 0xFF).toFloat()
                }
                val m = boxBlur(maxFilter(a, 2), 4)
                for (i in m.indices) m[i] = max(m[i], 40f)
                m
            }
            for (x in 0 until w) {
                val f = ((x + 0.5f) * sw / w - 0.5f).coerceIn(0f, (sw - 1).toFloat())
                x0[x] = f.toInt()
                x1[x] = min(x0[x] + 1, sw - 1)
                fx[x] = f - x0[x]
            }
            rowVals = Array(n) { FloatArray(sw) }
        }

        fun prepareRow(y: Int) {
            val f = ((y + 0.5f) * sh / h - 0.5f).coerceIn(0f, (sh - 1).toFloat())
            val y0 = f.toInt()
            val y1 = min(y0 + 1, sh - 1)
            val fy = f - y0
            for (k in ch.indices) {
                val a = ch[k]
                val r = rowVals[k]
                for (sx in 0 until sw) r[sx] = a[y0 * sw + sx] * (1 - fy) + a[y1 * sw + sx] * fy
            }
        }

        fun sample(k: Int, x: Int): Float {
            val r = rowVals[k]
            return r[x0[x]] * (1 - fx[x]) + r[x1[x]] * fx[x]
        }

        private fun maxFilter(a: FloatArray, rad: Int): FloatArray {
            val t = FloatArray(a.size)
            for (y in 0 until sh) for (x in 0 until sw) {
                var m = 0f
                for (d in -rad..rad) m = max(m, a[y * sw + (x + d).coerceIn(0, sw - 1)])
                t[y * sw + x] = m
            }
            val o = FloatArray(a.size)
            for (y in 0 until sh) for (x in 0 until sw) {
                var m = 0f
                for (d in -rad..rad) m = max(m, t[(y + d).coerceIn(0, sh - 1) * sw + x])
                o[y * sw + x] = m
            }
            return o
        }

        private fun boxBlur(a: FloatArray, rad: Int): FloatArray {
            val n = 2 * rad + 1
            val t = FloatArray(a.size)
            for (y in 0 until sh) for (x in 0 until sw) {
                var s = 0f
                for (d in -rad..rad) s += a[y * sw + (x + d).coerceIn(0, sw - 1)]
                t[y * sw + x] = s / n
            }
            val o = FloatArray(a.size)
            for (y in 0 until sh) for (x in 0 until sw) {
                var s = 0f
                for (d in -rad..rad) s += t[(y + d).coerceIn(0, sh - 1) * sw + x]
                o[y * sw + x] = s / n
            }
            return o
        }
    }

    // ---------- Utilidades de mapa de bits ----------

    fun scaleToMax(src: Bitmap, maxSide: Int?): Bitmap {
        if (maxSide == null) return src
        val side = max(src.width, src.height)
        if (side <= maxSide) return src
        val s = maxSide.toFloat() / side
        return Bitmap.createScaledBitmap(
            src, max(1, (src.width * s).roundToInt()), max(1, (src.height * s).roundToInt()), true,
        )
    }

    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        val d = ((degrees % 360) + 360) % 360
        if (d == 0) return src
        val m = Matrix().apply { postRotate(d.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** Decodifica con submuestreo y ajuste exacto al lado mayor [maxSide] (null = resolución original). */
    fun decode(file: File, maxSide: Int?): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        if (maxSide != null) {
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.path, opts) ?: error("No se pudo leer ${file.name}")
        val scaled = scaleToMax(decoded, maxSide)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    /** Decodifica → escala → rota → filtra, liberando cada intermedio. */
    suspend fun render(page: ScannedPage, maxSide: Int?): Bitmap = withContext(Dispatchers.Default) {
        val decoded = decode(File(page.base), maxSide)
        val rotated = rotate(decoded, page.rotation)
        if (rotated !== decoded) decoded.recycle()
        val filtered = applyBlocking(rotated, page.filter)
        if (filtered !== rotated) rotated.recycle()
        filtered
    }
}
