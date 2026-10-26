package bo.app.scanner.domain

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class ScanResult(val imageUris: List<Uri>, val pdfUri: Uri?)

/** Escáner ML Kit: detección de bordes, recorte por 4 vértices, corrección de perspectiva y lupa nativa. */
class ScannerEngine {

    fun options(pageLimit: Int = MAX_PAGES): GmsDocumentScannerOptions =
        GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(pageLimit)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF,
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()

    /** Lanza la UI nativa. Si Play Services no la ofrece, [onUnavailable] activa el fallback manual. */
    fun launch(
        activity: Activity,
        launcher: ActivityResultLauncher<IntentSenderRequest>,
        pageLimit: Int = MAX_PAGES,
        onUnavailable: (Exception) -> Unit,
    ) {
        GmsDocumentScanning.getClient(options(pageLimit))
            .getStartScanIntent(activity)
            .addOnSuccessListener { sender ->
                runCatching { launcher.launch(IntentSenderRequest.Builder(sender).build()) }
                    .onFailure { onUnavailable(it as? Exception ?: Exception(it)) }
            }
            .addOnFailureListener { onUnavailable(it) }
    }

    companion object {
        const val MAX_PAGES = 100
        const val MAX_SIDE = 4096

        fun parse(resultCode: Int, data: Intent?): ScanResult? {
            if (resultCode != Activity.RESULT_OK) return null
            val result = GmsDocumentScanningResult.fromActivityResultIntent(data) ?: return null
            val uris = result.pages?.map { it.imageUri }.orEmpty()
            return ScanResult(uris, result.pdf?.uri)
        }

        /** Copia la imagen a [dir] enderezada según EXIF y limitada a [MAX_SIDE]. */
        suspend fun importImage(context: Context, uri: Uri, dir: File): File = withContext(Dispatchers.Default) {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Imagen no válida" }

            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: error("No se pudo leer la imagen")

            val degrees = runCatching {
                resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
            }.getOrDefault(0)
            val rotated = if (degrees != 0) {
                val m = Matrix().apply { postRotate(degrees.toFloat()) }
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
                    .also { if (it !== decoded) decoded.recycle() }
            } else decoded
            val scaled = ImageFilterEngine.scaleToMax(rotated, MAX_SIDE)
            if (scaled !== rotated) rotated.recycle()

            val out = File(dir, "${UUID.randomUUID()}.jpg")
            FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            scaled.recycle()
            out
        }
    }
}
