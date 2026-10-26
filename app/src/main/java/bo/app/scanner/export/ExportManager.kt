package bo.app.scanner.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import bo.app.scanner.domain.ImageFilterEngine
import bo.app.scanner.domain.QualitySetting
import bo.app.scanner.domain.ScannedPage
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.min

typealias Progress = suspend (done: Int, total: Int) -> Unit

class ExportManager(private val context: Context) {

    /** PDF A4 con JPEG a la calidad elegida (PDFBox). Si PDFBox falla, PdfDocument nativo. */
    suspend fun exportToPdf(
        pages: List<ScannedPage>,
        quality: QualitySetting,
        targetUri: Uri,
        onProgress: Progress = { _, _ -> },
    ) = withContext(Dispatchers.Default) {
        try {
            pdfWithPdfBox(pages, quality, targetUri, onProgress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            pdfNative(pages, quality, targetUri, onProgress)
        }
    }

    private suspend fun pdfWithPdfBox(
        pages: List<ScannedPage>,
        quality: QualitySetting,
        targetUri: Uri,
        onProgress: Progress,
    ) {
        PDDocument(MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)).use { doc ->
            pages.forEachIndexed { i, page ->
                val bmp = ImageFilterEngine.render(page, quality.maxSide)
                val image = try {
                    JPEGFactory.createFromImage(doc, bmp, quality.jpeg)
                } finally {
                    bmp.recycle()
                }
                val box = a4(image.width, image.height)
                val pdPage = PDPage(PDRectangle(box.width(), box.height()))
                doc.addPage(pdPage)
                val r = fit(image.width, image.height, box.width(), box.height())
                PDPageContentStream(doc, pdPage).use { it.drawImage(image, r.left, r.top, r.width(), r.height()) }
                onProgress(i + 1, pages.size)
            }
            withContext(Dispatchers.IO) { open(targetUri).use { doc.save(it) } }
        }
    }

    private suspend fun pdfNative(
        pages: List<ScannedPage>,
        quality: QualitySetting,
        targetUri: Uri,
        onProgress: Progress,
    ) {
        val doc = PdfDocument()
        try {
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            pages.forEachIndexed { i, page ->
                val bmp = ImageFilterEngine.render(page, quality.maxSide)
                val box = a4(bmp.width, bmp.height)
                val info = PdfDocument.PageInfo.Builder(box.width().toInt(), box.height().toInt(), i + 1).create()
                val pdfPage = doc.startPage(info)
                pdfPage.canvas.drawBitmap(bmp, null, fit(bmp.width, bmp.height, box.width(), box.height()), paint)
                doc.finishPage(pdfPage)
                bmp.recycle()
                onProgress(i + 1, pages.size)
            }
            withContext(Dispatchers.IO) { open(targetUri).use { doc.writeTo(it) } }
        } finally {
            doc.close()
        }
    }

    /** DOCX OpenXML mínimo: una imagen a página completa (A4, márgenes 1 cm) por página. */
    suspend fun exportToDocx(
        pages: List<ScannedPage>,
        targetUri: Uri,
        quality: QualitySetting = QualitySetting.ALTA,
        onProgress: Progress = { _, _ -> },
    ) = withContext(Dispatchers.Default) {
        val sizes = ArrayList<Pair<Int, Int>>(pages.size)
        withContext(Dispatchers.IO) { open(targetUri) }.use { raw ->
            ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                zip.entry("[Content_Types].xml", CONTENT_TYPES)
                zip.entry("_rels/.rels", ROOT_RELS)
                pages.forEachIndexed { i, page ->
                    val bmp = ImageFilterEngine.render(page, quality.maxSide)
                    val bytes = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, (quality.jpeg * 100).toInt(), bytes)
                    sizes += bmp.width to bmp.height
                    bmp.recycle()
                    withContext(Dispatchers.IO) {
                        zip.putNextEntry(ZipEntry("word/media/pagina${i + 1}.jpeg"))
                        bytes.writeTo(zip)
                        zip.closeEntry()
                    }
                    onProgress(i + 1, pages.size)
                }
                zip.entry("word/_rels/document.xml.rels", documentRels(sizes.size))
                zip.entry("word/document.xml", documentXml(sizes))
            }
        }
    }

    private suspend fun ZipOutputStream.entry(name: String, text: String) = withContext(Dispatchers.IO) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun open(uri: Uri): OutputStream {
        val r = context.contentResolver
        return runCatching { r.openOutputStream(uri, "wt") }.getOrNull()
            ?: r.openOutputStream(uri, "w")
            ?: error("No se pudo abrir el destino")
    }

    companion object {
        private const val A4_W = 595.28f
        private const val A4_H = 841.89f

        private fun a4(w: Int, h: Int) = if (w > h) RectF(0f, 0f, A4_H, A4_W) else RectF(0f, 0f, A4_W, A4_H)

        /** Rectángulo centrado que encaja w×h en bw×bh conservando la proporción. */
        private fun fit(w: Int, h: Int, bw: Float, bh: Float): RectF {
            val s = min(bw / w, bh / h)
            val dw = w * s
            val dh = h * s
            val l = (bw - dw) / 2f
            val t = (bh - dh) / 2f
            return RectF(l, t, l + dw, t + dh)
        }

        // A4 en twips, márgenes de 1 cm; EMU = twip × 635.
        private const val PAGE_W = 11906
        private const val PAGE_H = 16838
        private const val MARGIN = 567
        private const val EMU_PER_TWIP = 635L

        private const val CONTENT_TYPES =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
                """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
                """<Default Extension="xml" ContentType="application/xml"/>""" +
                """<Default Extension="jpeg" ContentType="image/jpeg"/>""" +
                """<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>""" +
                """</Types>"""

        private const val ROOT_RELS =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>""" +
                """</Relationships>"""

        private fun documentRels(n: Int) = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
            for (i in 1..n) {
                append("""<Relationship Id="rIdImg$i" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/pagina$i.jpeg"/>""")
            }
            append("</Relationships>")
        }

        private fun documentXml(sizes: List<Pair<Int, Int>>) = buildString {
            val areaW = (PAGE_W - 2 * MARGIN) * EMU_PER_TWIP
            val areaH = ((PAGE_H - 2 * MARGIN) * 0.98).toLong() * EMU_PER_TWIP
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append(
                """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" """ +
                    """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" """ +
                    """xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" """ +
                    """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" """ +
                    """xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><w:body>""",
            )
            sizes.forEachIndexed { idx, (w, h) ->
                val n = idx + 1
                val s = min(areaW.toDouble() / w, areaH.toDouble() / h)
                val cx = (w * s).toLong()
                val cy = (h * s).toLong()
                append("<w:p><w:pPr>")
                if (idx > 0) append("<w:pageBreakBefore/>")
                append("""<w:spacing w:before="0" w:after="0" w:line="240" w:lineRule="auto"/><w:jc w:val="center"/></w:pPr>""")
                append("""<w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0">""")
                append("""<wp:extent cx="$cx" cy="$cy"/><wp:docPr id="$n" name="Pagina $n"/>""")
                append("""<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect="1"/></wp:cNvGraphicFramePr>""")
                append("""<a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic>""")
                append("""<pic:nvPicPr><pic:cNvPr id="$n" name="pagina$n.jpeg"/><pic:cNvPicPr/></pic:nvPicPr>""")
                append("""<pic:blipFill><a:blip r:embed="rIdImg$n"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>""")
                append("""<pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm>""")
                append("""<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr>""")
                append("</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>")
            }
            append("""<w:sectPr><w:pgSz w:w="$PAGE_W" w:h="$PAGE_H"/>""")
            append("""<w:pgMar w:top="$MARGIN" w:right="$MARGIN" w:bottom="$MARGIN" w:left="$MARGIN" w:header="0" w:footer="0" w:gutter="0"/>""")
            append("</w:sectPr></w:body></w:document>")
        }
    }
}
