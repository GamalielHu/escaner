package bo.app.scanner.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import bo.app.scanner.domain.FilterType
import bo.app.scanner.domain.ImageFilterEngine
import bo.app.scanner.domain.QualitySetting
import bo.app.scanner.domain.ScannedPage
import bo.app.scanner.domain.ScannerEngine
import bo.app.scanner.export.ExportManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

enum class ExportKind(val mime: String, val ext: String) {
    PDF("application/pdf", "pdf"),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
}

data class Notice(val text: String, val shareUri: Uri? = null, val mime: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
class ScannerViewModel(app: Application) : AndroidViewModel(app) {

    private val dir = File(app.filesDir, "paginas").apply { mkdirs() }
    private val session = File(app.filesDir, "sesion.json")
    private val prefs = app.getSharedPreferences("ajustes", 0)
    private val exporter = ExportManager(app)
    private val writer = Dispatchers.IO.limitedParallelism(1)

    val pages = mutableStateListOf<ScannedPage>()
    var quality by mutableStateOf(
        runCatching { QualitySetting.valueOf(prefs.getString("calidad", null)!!) }.getOrDefault(QualitySetting.MEDIA),
    )
        private set
    var busy by mutableStateOf<String?>(null)
        private set

    private val _notices = MutableSharedFlow<Notice>(extraBufferCapacity = 4)
    val notices: SharedFlow<Notice> = _notices

    init {
        viewModelScope.launch { pages.addAll(withContext(Dispatchers.IO) { load() }) }
    }

    fun page(id: String) = pages.firstOrNull { it.id == id }

    fun notify(text: String) {
        _notices.tryEmit(Notice(text))
    }

    fun selectQuality(q: QualitySetting) {
        quality = q
        prefs.edit().putString("calidad", q.name).apply()
    }

    fun addFromUris(uris: List<Uri>, onDone: (List<String>) -> Unit = {}) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            busy = "Importando…"
            val added = ArrayList<String>()
            for ((i, uri) in uris.withIndex()) {
                busy = "Importando ${i + 1} de ${uris.size}…"
                runCatching { ScannerEngine.importImage(getApplication(), uri, dir) }
                    .onSuccess { f ->
                        val p = ScannedPage(UUID.randomUUID().toString(), f.path, f.path)
                        pages += p
                        added += p.id
                    }
                    .onFailure { notify("No se pudo importar una imagen: ${it.message}") }
            }
            busy = null
            save()
            onDone(added)
        }
    }

    fun replace(id: String, uri: Uri, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            busy = "Reemplazando…"
            runCatching { ScannerEngine.importImage(getApplication(), uri, dir) }
                .onSuccess { f ->
                    val i = pages.indexOfFirst { it.id == id }
                    if (i >= 0) {
                        val old = pages[i]
                        pages[i] = old.copy(original = f.path, base = f.path, rotation = 0, corners = null)
                        deleteFiles(old)
                    }
                }
                .onFailure { notify("No se pudo reemplazar: ${it.message}") }
            busy = null
            save()
            onDone()
        }
    }

    fun rotate(id: String, delta: Int = 90) = update(id) { it.copy(rotation = (it.rotation + delta + 360) % 360) }

    fun setFilter(id: String, f: FilterType) = update(id) { it.copy(filter = f) }

    fun setFilterAll(f: FilterType) {
        for (i in pages.indices) pages[i] = pages[i].copy(filter = f)
        save()
    }

    fun delete(id: String) {
        val p = page(id) ?: return
        pages.remove(p)
        deleteFiles(p)
        save()
    }

    fun clear() {
        val all = pages.toList()
        pages.clear()
        all.forEach(::deleteFiles)
        save()
    }

    fun move(from: Int, to: Int) {
        if (from !in pages.indices || to !in pages.indices || from == to) return
        pages.add(to, pages.removeAt(from))
    }

    fun commitOrder() = save()

    fun applyCrop(id: String, corners: List<Float>, onDone: () -> Unit) {
        val p = page(id) ?: return
        viewModelScope.launch {
            busy = "Corrigiendo perspectiva…"
            runCatching {
                withContext(Dispatchers.Default) {
                    val src = ImageFilterEngine.decode(File(p.original), ScannerEngine.MAX_SIDE)
                    val out = PerspectiveCorrector.warp(src, corners)
                    src.recycle()
                    val f = File(dir, "${UUID.randomUUID()}.jpg")
                    FileOutputStream(f).use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    out.recycle()
                    f
                }
            }.onSuccess { f ->
                val i = pages.indexOfFirst { it.id == id }
                if (i >= 0) {
                    val old = pages[i]
                    if (old.base != old.original) File(old.base).delete()
                    pages[i] = old.copy(base = f.path, corners = corners)
                } else f.delete()
            }.onFailure { notify("No se pudo recortar: ${it.message}") }
            busy = null
            save()
            onDone()
        }
    }

    fun export(kind: ExportKind, uri: Uri) {
        val snapshot = pages.toList()
        if (snapshot.isEmpty()) return
        viewModelScope.launch {
            busy = "Exportando…"
            val progress: suspend (Int, Int) -> Unit = { d, t ->
                withContext(Dispatchers.Main) { busy = "Exportando página $d de $t…" }
            }
            runCatching {
                when (kind) {
                    ExportKind.PDF -> exporter.exportToPdf(snapshot, quality, uri, progress)
                    ExportKind.DOCX -> exporter.exportToDocx(snapshot, uri, quality, progress)
                }
            }.onSuccess {
                _notices.tryEmit(Notice("${kind.name} guardado (${snapshot.size} páginas)", uri, kind.mime))
            }.onFailure {
                notify("Error al exportar: ${it.message}")
            }
            busy = null
        }
    }

    private fun update(id: String, f: (ScannedPage) -> ScannedPage) {
        val i = pages.indexOfFirst { it.id == id }
        if (i < 0) return
        pages[i] = f(pages[i])
        save()
    }

    private fun deleteFiles(p: ScannedPage) {
        File(p.original).delete()
        if (p.base != p.original) File(p.base).delete()
    }

    private fun save() {
        val snapshot = pages.toList()
        viewModelScope.launch(writer) {
            val arr = JSONArray()
            snapshot.forEach { p ->
                arr.put(
                    JSONObject()
                        .put("id", p.id)
                        .put("original", p.original)
                        .put("base", p.base)
                        .put("rotation", p.rotation)
                        .put("filter", p.filter.name)
                        .put("corners", p.corners?.let { c -> JSONArray(c.map { it.toDouble() }) }),
                )
            }
            runCatching { session.writeText(arr.toString()) }
        }
    }

    private fun load(): List<ScannedPage> = runCatching {
        if (!session.exists()) return emptyList()
        val arr = JSONArray(session.readText())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val base = o.getString("base")
            if (!File(base).exists()) return@mapNotNull null
            ScannedPage(
                id = o.getString("id"),
                original = o.getString("original"),
                base = base,
                rotation = o.optInt("rotation"),
                filter = runCatching { FilterType.valueOf(o.getString("filter")) }.getOrDefault(FilterType.ORIGINAL),
                corners = o.optJSONArray("corners")?.let { c -> (0 until c.length()).map { c.getDouble(it).toFloat() } },
            )
        }
    }.getOrDefault(emptyList())
}
