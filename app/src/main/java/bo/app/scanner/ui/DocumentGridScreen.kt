package bo.app.scanner.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import bo.app.scanner.domain.FilterType
import bo.app.scanner.domain.ImageFilterEngine
import bo.app.scanner.domain.QualitySetting
import bo.app.scanner.domain.ScannedPage
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import coil.transform.Transformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Rotación + filtro aplicados por Coil sobre la miniatura (fuera del hilo principal). */
class PageTransformation(private val rotation: Int, private val filter: FilterType) : Transformation {
    override val cacheKey = "pagina-$rotation-${filter.name}"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap = withContext(Dispatchers.Default) {
        val rotated = ImageFilterEngine.rotate(input, rotation)
        val out = ImageFilterEngine.applyBlocking(rotated, filter)
        if (rotated !== input && rotated !== out) rotated.recycle()
        out
    }
}

@Composable
fun PageImage(page: ScannedPage, sizePx: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val request = remember(page.base, page.rotation, page.filter, sizePx) {
        ImageRequest.Builder(context)
            .data(File(page.base))
            .size(sizePx)
            .allowHardware(false)
            .transformations(PageTransformation(page.rotation, page.filter))
            .crossfade(true)
            .build()
    }
    AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Fit, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentGridScreen(
    vm: ScannerViewModel,
    snackbar: SnackbarHostState,
    onScan: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onOpen: (String) -> Unit,
    onCrop: (String) -> Unit,
    onReplace: (String) -> Unit,
    onExport: (ExportKind) -> Unit,
) {
    val pages = vm.pages
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Escáner", fontWeight = FontWeight.Bold)
                        Text(
                            if (pages.isEmpty()) "Desarrollado por: Gamaliel Huasco" else "${pages.size} páginas · mantén pulsado para reordenar",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "Más") }
                    DropdownMenu(menuOpen, { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Importar de la galería") }, onClick = { menuOpen = false; onGallery() })
                        DropdownMenuItem(text = { Text("Cámara manual") }, onClick = { menuOpen = false; onCamera() })
                        if (pages.isNotEmpty()) {
                            DropdownMenuItem(text = { Text("Nuevo documento") }, onClick = { menuOpen = false; confirmClear = true })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onScan,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(if (pages.isEmpty()) "Escanear" else "Añadir") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { if (pages.isNotEmpty()) ExportBar(vm.quality, vm::selectQuality, vm.busy == null, onExport) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            vm.busy?.let {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
            }
            if (pages.isEmpty()) EmptyState(onScan, onGallery)
            else ReorderableGrid(
                pages = pages,
                onMove = vm::move,
                onMoveDone = vm::commitOrder,
                onOpen = onOpen,
                onRotate = { vm.rotate(it) },
                onCrop = onCrop,
                onReplace = onReplace,
                onDelete = vm::delete,
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Nuevo documento") },
            text = { Text("Se borrarán las ${pages.size} páginas actuales.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.clear() }) { Text("Borrar") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun EmptyState(onScan: () -> Unit, onGallery: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Sin páginas", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Escanea documentos con detección automática de bordes o importa fotos de la galería.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onScan) { Text("Escanear documento") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onGallery) { Text("Importar de la galería") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportBar(
    quality: QualitySetting,
    onQuality: (QualitySetting) -> Unit,
    enabled: Boolean,
    onExport: (ExportKind) -> Unit,
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("Calidad de exportación", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val all = QualitySetting.entries
                all.forEachIndexed { i, q ->
                    SegmentedButton(
                        selected = q == quality,
                        onClick = { onQuality(q) },
                        shape = SegmentedButtonDefaults.itemShape(i, all.size),
                    ) { Text(q.label) }
                }
            }
            Text(
                when (quality) {
                    QualitySetting.ALTA -> "Resolución original · JPEG 90%"
                    QualitySetting.MEDIA -> "Máx. 1600 px · JPEG 70%"
                    QualitySetting.ECONOMICA -> "Máx. 1080 px · JPEG 50%"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { onExport(ExportKind.PDF) }, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Text("Guardar PDF")
                }
                OutlinedButton(onClick = { onExport(ExportKind.DOCX) }, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Text("Guardar Word")
                }
            }
        }
    }
}

@Composable
private fun ReorderableGrid(
    pages: List<ScannedPage>,
    onMove: (Int, Int) -> Unit,
    onMoveDone: () -> Unit,
    onOpen: (String) -> Unit,
    onRotate: (String) -> Unit,
    onCrop: (String) -> Unit,
    onReplace: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val state = rememberLazyGridState()
    var dragging by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val move by rememberUpdatedState(onMove)
    val done by rememberUpdatedState(onMoveDone)

    fun LazyGridItemInfo.contains(p: Offset) =
        p.x >= offset.x && p.x <= offset.x + size.width && p.y >= offset.y && p.y <= offset.y + size.height

    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        state = state,
        contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragStart = { pos ->
                    dragging = state.layoutInfo.visibleItemsInfo.firstOrNull { it.contains(pos) }?.index
                    dragOffset = Offset.Zero
                },
                onDrag = { change, amount ->
                    val from = dragging ?: return@detectDragGesturesAfterLongPress
                    change.consume()
                    dragOffset += amount
                    val items = state.layoutInfo.visibleItemsInfo
                    val cur = items.firstOrNull { it.index == from } ?: return@detectDragGesturesAfterLongPress
                    val center = Offset(
                        cur.offset.x + cur.size.width / 2f + dragOffset.x,
                        cur.offset.y + cur.size.height / 2f + dragOffset.y,
                    )
                    val target = items.firstOrNull { it.index != from && it.contains(center) }
                    if (target != null) {
                        move(from, target.index)
                        dragOffset += Offset(
                            (cur.offset.x - target.offset.x).toFloat(),
                            (cur.offset.y - target.offset.y).toFloat(),
                        )
                        dragging = target.index
                    }
                },
                onDragEnd = { dragging = null; dragOffset = Offset.Zero; done() },
                onDragCancel = { dragging = null; dragOffset = Offset.Zero; done() },
            )
        },
    ) {
        itemsIndexed(pages, key = { _, p -> p.id }) { index, page ->
            val isDragged = index == dragging
            val mod = if (isDragged) {
                Modifier.zIndex(1f).graphicsLayer {
                    translationX = dragOffset.x
                    translationY = dragOffset.y
                    scaleX = 1.05f
                    scaleY = 1.05f
                }.shadow(12.dp, RoundedCornerShape(12.dp))
            } else Modifier.animateItem()
            PageCard(
                page = page,
                number = index + 1,
                modifier = mod,
                onOpen = { onOpen(page.id) },
                onRotate = { onRotate(page.id) },
                onCrop = { onCrop(page.id) },
                onReplace = { onReplace(page.id) },
                onDelete = { onDelete(page.id) },
            )
        }
    }
}

@Composable
private fun PageCard(
    page: ScannedPage,
    number: Int,
    modifier: Modifier,
    onOpen: () -> Unit,
    onRotate: () -> Unit,
    onCrop: () -> Unit,
    onReplace: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(onClick = onOpen, modifier = modifier, colors = CardDefaults.cardColors()) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.75f).background(Color(0xFFECEFF1))) {
            PageImage(page, 480, Modifier.fillMaxSize().padding(6.dp))
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(bottomEnd = 10.dp),
                modifier = Modifier.align(Alignment.TopStart),
            ) {
                Text(
                    "$number",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                page.filter.label,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f).padding(start = 10.dp),
            )
            IconButton(onClick = onRotate) { Icon(Icons.Default.Refresh, "Rotar 90°") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Acciones") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Rotar 90°") }, onClick = { menu = false; onRotate() })
                    DropdownMenuItem(text = { Text("Ajustar bordes") }, onClick = { menu = false; onCrop() })
                    DropdownMenuItem(text = { Text("Reemplazar captura") }, onClick = { menu = false; onReplace() })
                    DropdownMenuItem(text = { Text("Eliminar") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

/** Vista de una página: filtros, rotación, recorte, reemplazo y borrado. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageEditorScreen(
    vm: ScannerViewModel,
    pageId: String,
    onBack: () -> Unit,
    onCrop: (String) -> Unit,
    onReplace: (String) -> Unit,
) {
    val page = vm.page(pageId)
    if (page == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val number = vm.pages.indexOf(page) + 1
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Página $number de ${vm.pages.size}") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Volver") } },
                actions = {
                    IconButton(onClick = { vm.delete(page.id); onBack() }) { Icon(Icons.Default.Delete, "Eliminar") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Box(
                Modifier.weight(1f).fillMaxWidth().background(Color(0xFF202124)).padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                PageImage(page, 1600, Modifier.fillMaxSize())
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterType.entries.forEach { f ->
                    FilterChip(
                        selected = page.filter == f,
                        onClick = { vm.setFilter(page.id, f) },
                        label = { Text(f.label) },
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                EditorAction("Rotar", Icons.Default.Refresh) { vm.rotate(page.id) }
                EditorAction("Bordes", Icons.Default.Edit) { onCrop(page.id) }
                EditorAction("Reemplazar", Icons.Default.Add) { onReplace(page.id) }
            }
            TextButton(
                onClick = { vm.setFilterAll(page.filter); vm.notify("Filtro ${page.filter.label} aplicado a todas") },
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp),
            ) { Text("Aplicar «${page.filter.label}» a todas las páginas") }
        }
    }
}

@Composable
private fun EditorAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) { Icon(icon, label) }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
