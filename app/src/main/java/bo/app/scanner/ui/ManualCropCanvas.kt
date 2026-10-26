package bo.app.scanner.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import bo.app.scanner.domain.ImageFilterEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val LOUPE_ZOOM = 2f
private const val PREVIEW_SIDE = 2048
private val DEFAULT_CORNERS = listOf(0.04f, 0.04f, 0.96f, 0.04f, 0.96f, 0.96f, 0.04f, 0.96f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualCropScreen(
    source: File,
    initialCorners: List<Float>?,
    onCancel: () -> Unit,
    onApply: (List<Float>) -> Unit,
) {
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    val corners = remember {
        val c = initialCorners?.takeIf { it.size == 8 } ?: DEFAULT_CORNERS
        mutableStateListOf(Offset(c[0], c[1]), Offset(c[2], c[3]), Offset(c[4], c[5]), Offset(c[6], c[7]))
    }
    LaunchedEffect(source) {
        image = withContext(Dispatchers.Default) { ImageFilterEngine.decode(source, PREVIEW_SIDE) }.asImageBitmap()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajustar bordes") },
                navigationIcon = {
                    IconButton(onClick = onCancel) { Icon(Icons.Default.ArrowBack, "Cancelar") }
                },
                actions = {
                    IconButton(onClick = {
                        listOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f).chunked(2)
                            .forEachIndexed { i, p -> corners[i] = Offset(p[0], p[1]) }
                    }) { Icon(Icons.Default.Refresh, "Página completa") }
                    TextButton(onClick = {
                        onApply(PerspectiveCorrector.orderCorners(corners.flatMap { listOf(it.x, it.y) }))
                    }) {
                        Icon(Icons.Default.Check, null)
                        Text("Aplicar")
                    }
                },
            )
        },
    ) { pad ->
        Box(
            Modifier.padding(pad).fillMaxSize().background(Color(0xFF202124)),
            contentAlignment = Alignment.Center,
        ) {
            val img = image
            if (img == null) CircularProgressIndicator()
            else ManualCropCanvas(img, corners, Modifier.fillMaxSize().padding(24.dp))
        }
    }
}

/** Lienzo con 4 vértices arrastrables (coordenadas normalizadas) y lupa circular 2x. */
@Composable
fun ManualCropCanvas(image: ImageBitmap, corners: SnapshotStateList<Offset>, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val handleR = with(density) { 11.dp.toPx() }
    val touchR = with(density) { 56.dp.toPx() }
    val loupeR = with(density) { 62.dp.toPx() }
    val loupeGap = with(density) { 96.dp.toPx() }
    val stroke = with(density) { 2.dp.toPx() }
    val accent = MaterialTheme.colorScheme.primary

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var active by remember { mutableIntStateOf(-1) }

    val scale = if (canvasSize.width == 0) 1f
    else min(canvasSize.width.toFloat() / image.width, canvasSize.height.toFloat() / image.height)
    val left = (canvasSize.width - image.width * scale) / 2f
    val top = (canvasSize.height - image.height * scale) / 2f
    val dispW = image.width * scale
    val dispH = image.height * scale
    fun toScreen(p: Offset) = Offset(left + p.x * dispW, top + p.y * dispH)

    Canvas(
        modifier
            .onSizeChanged { canvasSize = it }
            .pointerInput(scale, left, top) {
                detectDragGestures(
                    onDragStart = { pos ->
                        var best = -1
                        var bestD = touchR
                        corners.forEachIndexed { i, c ->
                            val d = (toScreen(c) - pos).getDistance()
                            if (d < bestD) {
                                bestD = d
                                best = i
                            }
                        }
                        active = best
                    },
                    onDragEnd = { active = -1 },
                    onDragCancel = { active = -1 },
                    onDrag = { change, amount ->
                        val i = active
                        if (i >= 0) {
                            change.consume()
                            val c = corners[i]
                            corners[i] = Offset(
                                (c.x + amount.x / dispW).coerceIn(0f, 1f),
                                (c.y + amount.y / dispH).coerceIn(0f, 1f),
                            )
                        }
                    },
                )
            },
    ) {
        drawImage(
            image,
            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
            dstSize = IntSize(dispW.roundToInt(), dispH.roundToInt()),
        )
        val pts = corners.map { toScreen(it) }
        val poly = polygon(pts)
        val outside = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(0f, 0f, size.width, size.height))
            addPath(poly)
        }
        drawPath(outside, Color.Black.copy(alpha = 0.45f))
        drawPath(poly, accent, style = Stroke(stroke))
        pts.forEachIndexed { i, p ->
            drawCircle(Color.White, handleR, p)
            drawCircle(if (i == active) accent else accent.copy(alpha = 0.8f), handleR - stroke, p)
        }

        val i = active
        if (i >= 0) drawLoupe(image, corners[i], pts[i], pts, scale, loupeR, loupeGap, accent, stroke)
    }
}

private fun polygon(pts: List<Offset>) = Path().apply {
    moveTo(pts[0].x, pts[0].y)
    for (k in 1 until pts.size) lineTo(pts[k].x, pts[k].y)
    close()
}

private fun DrawScope.drawLoupe(
    image: ImageBitmap,
    norm: Offset,
    anchor: Offset,
    pts: List<Offset>,
    scale: Float,
    r: Float,
    gap: Float,
    accent: Color,
    stroke: Float,
) {
    var center = Offset(anchor.x, anchor.y - gap)
    if (center.y - r < 0f) center = Offset(anchor.x, anchor.y + gap)
    center = Offset(center.x.coerceIn(r, max(r, size.width - r)), center.y)
    val circle = Path().apply { addOval(Rect(center, r)) }

    clipPath(circle) {
        drawRect(Color.Black, topLeft = center - Offset(r, r), size = Size(2 * r, 2 * r))
        val k = scale * LOUPE_ZOOM
        val half = r / k
        val ix = norm.x * image.width
        val iy = norm.y * image.height
        val l = ix - half
        val t = iy - half
        val cl = max(0f, l)
        val ct = max(0f, t)
        val cr = min(image.width.toFloat(), ix + half)
        val cb = min(image.height.toFloat(), iy + half)
        val sw = (cr - cl).toInt()
        val sh = (cb - ct).toInt()
        if (sw > 0 && sh > 0) {
            drawImage(
                image,
                srcOffset = IntOffset(cl.toInt(), ct.toInt()),
                srcSize = IntSize(sw, sh),
                dstOffset = IntOffset(
                    (center.x - r + (cl - l) * k).roundToInt(),
                    (center.y - r + (ct - t) * k).roundToInt(),
                ),
                dstSize = IntSize((sw * k).roundToInt(), (sh * k).roundToInt()),
            )
        }
        val mapped = pts.map { center + (it - anchor) * LOUPE_ZOOM }
        drawPath(polygon(mapped), accent, style = Stroke(stroke))
        drawLine(Color.White, center - Offset(r * 0.25f, 0f), center + Offset(r * 0.25f, 0f), stroke)
        drawLine(Color.White, center - Offset(0f, r * 0.25f), center + Offset(0f, r * 0.25f), stroke)
    }
    drawCircle(Color.White, r, center, style = Stroke(stroke * 1.5f))
}

/** Corrección de perspectiva del cuadrilátero al rectángulo plano mediante Matrix.setPolyToPoly. */
object PerspectiveCorrector {

    private const val MAX_SIDE = 4096

    /** Ordena 4 puntos (x,y intercalados) como TL, TR, BR, BL según su ángulo alrededor del centroide. */
    fun orderCorners(c: List<Float>): List<Float> {
        val pts = c.chunked(2).map { it[0] to it[1] }
        val cx = pts.sumOf { it.first.toDouble() } / 4
        val cy = pts.sumOf { it.second.toDouble() } / 4
        return pts.sortedBy { atan2(it.second - cy, it.first - cx) }.flatMap { listOf(it.first, it.second) }
    }

    /** [corners] normalizados 0..1 en orden TL, TR, BR, BL. Ejecutar en Dispatchers.Default. */
    fun warp(src: Bitmap, corners: List<Float>): Bitmap {
        val p = FloatArray(8) { i -> corners[i] * if (i % 2 == 0) src.width else src.height }
        fun d(a: Int, b: Int) = hypot(p[2 * a] - p[2 * b], p[2 * a + 1] - p[2 * b + 1])
        var w = max(d(0, 1), d(3, 2))
        var h = max(d(0, 3), d(1, 2))
        val big = max(w, h)
        if (big > MAX_SIDE) {
            w *= MAX_SIDE / big
            h *= MAX_SIDE / big
        }
        val ow = max(1, w.roundToInt())
        val oh = max(1, h.roundToInt())
        val dst = floatArrayOf(0f, 0f, ow.toFloat(), 0f, ow.toFloat(), oh.toFloat(), 0f, oh.toFloat())
        val m = Matrix()
        if (!m.setPolyToPoly(p, 0, dst, 0, 4)) return src.copy(Bitmap.Config.ARGB_8888, false)
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }
}
