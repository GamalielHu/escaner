package bo.app.scanner

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import bo.app.scanner.domain.ScannerEngine
import bo.app.scanner.ui.CameraCaptureScreen
import bo.app.scanner.ui.DocumentGridScreen
import bo.app.scanner.ui.ExportKind
import bo.app.scanner.ui.ManualCropScreen
import bo.app.scanner.ui.PageEditorScreen
import bo.app.scanner.ui.ScannerViewModel
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed interface Screen {
    data object Grid : Screen
    data class Editor(val id: String) : Screen
    data class Crop(val id: String, val back: Screen) : Screen
    data class Camera(val replaceId: String?) : Screen
}

private val Colors = lightColorScheme(
    primary = Color(0xFF0B5CAD),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E3FF),
    secondary = Color(0xFF3A6EA5),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        enableEdgeToEdge()
        setContent { MaterialTheme(colorScheme = Colors) { App(this) } }
    }
}

@Composable
private fun App(activity: ComponentActivity, vm: ScannerViewModel = viewModel()) {
    val engine = remember { ScannerEngine() }
    val snackbar = remember { SnackbarHostState() }
    var screen by remember { mutableStateOf<Screen>(Screen.Grid) }
    var replaceTarget by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        vm.notices.collect { n ->
            val uri = n.shareUri
            val r = snackbar.showSnackbar(n.text, actionLabel = if (uri != null) "Compartir" else null, withDismissAction = true)
            if (r == SnackbarResult.ActionPerformed && uri != null) share(activity, uri, n.mime ?: "*/*")
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val target = replaceTarget
        replaceTarget = null
        val result = ScannerEngine.parse(res.resultCode, res.data) ?: return@rememberLauncherForActivityResult
        if (target != null) result.imageUris.firstOrNull()?.let { vm.replace(target, it) }
        else vm.addFromUris(result.imageUris)
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        vm.addFromUris(uris)
    }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportKind.PDF.mime)) { uri ->
        if (uri != null) vm.export(ExportKind.PDF, uri)
    }
    val docxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportKind.DOCX.mime)) { uri ->
        if (uri != null) vm.export(ExportKind.DOCX, uri)
    }

    fun scan(replaceId: String?) {
        replaceTarget = replaceId
        engine.launch(activity, scanLauncher, if (replaceId != null) 1 else ScannerEngine.MAX_PAGES) {
            replaceTarget = null
            vm.notify("Escáner automático no disponible: usa la cámara manual")
            screen = Screen.Camera(replaceId)
        }
    }

    BackHandler(enabled = screen != Screen.Grid) {
        screen = when (val s = screen) {
            is Screen.Crop -> s.back
            else -> Screen.Grid
        }
    }

    when (val s = screen) {
        Screen.Grid -> DocumentGridScreen(
            vm = vm,
            snackbar = snackbar,
            onScan = { scan(null) },
            onCamera = { screen = Screen.Camera(null) },
            onGallery = {
                galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onOpen = { screen = Screen.Editor(it) },
            onCrop = { screen = Screen.Crop(it, Screen.Grid) },
            onReplace = { scan(it) },
            onExport = { kind ->
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val name = "Escaneo_$stamp.${kind.ext}"
                if (kind == ExportKind.PDF) pdfLauncher.launch(name) else docxLauncher.launch(name)
            },
        )

        is Screen.Editor -> PageEditorScreen(
            vm = vm,
            pageId = s.id,
            onBack = { screen = Screen.Grid },
            onCrop = { screen = Screen.Crop(it, s) },
            onReplace = { scan(it) },
        )

        is Screen.Crop -> {
            val page = vm.page(s.id)
            if (page == null) LaunchedEffect(Unit) { screen = Screen.Grid }
            else ManualCropScreen(
                source = File(page.original),
                initialCorners = page.corners,
                onCancel = { screen = s.back },
                onApply = { corners -> vm.applyCrop(page.id, corners) { screen = s.back } },
            )
        }

        is Screen.Camera -> CameraCaptureScreen(
            onCaptured = { file ->
                val uri = Uri.fromFile(file)
                val target = s.replaceId
                if (target != null) {
                    vm.replace(target, uri) { file.delete(); screen = Screen.Crop(target, Screen.Grid) }
                } else {
                    vm.addFromUris(listOf(uri)) { ids ->
                        file.delete()
                        ids.firstOrNull()?.let { screen = Screen.Crop(it, Screen.Grid) }
                    }
                }
            },
            onClose = { screen = Screen.Grid },
        )
    }
}

private fun share(activity: ComponentActivity, uri: Uri, mime: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        activity.startActivity(Intent.createChooser(send, "Compartir"))
    } catch (_: ActivityNotFoundException) {
    }
}
