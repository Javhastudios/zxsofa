package com.zxsofa.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.zxsofa.ZXSofaApp
import com.zxsofa.data.GameRepository
import com.zxsofa.data.Magazine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Biblioteca y lector de revistas en PDF. Se abre desde el catálogo o desde el
 * menú de pausa del juego (que se queda congelado mientras lees).
 */
class MagazineActivity : ComponentActivity() {

    companion object {
        fun intent(ctx: Context) = Intent(ctx, MagazineActivity::class.java)

        /** Abre un documento suelto (instrucciones de un juego): PDF, TXT o imagen. */
        fun docIntent(ctx: Context, path: String, title: String) =
            Intent(ctx, MagazineActivity::class.java).putExtra("docPath", path).putExtra("docTitle", title)
    }

    private val repo: GameRepository get() = (application as ZXSofaApp).repository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val docPath = intent.getStringExtra("docPath")
        if (docPath != null) {
            val title = intent.getStringExtra("docTitle") ?: File(docPath).name
            setContent { ZXSofaTheme { DocumentViewer(repo, docPath, title, onClose = { finish() }) } }
            return
        }
        lifecycleScope.launch { repo.refreshMagazines() }
        setContent {
            ZXSofaTheme {
                var openId by remember { mutableLongStateOf(-1L) }
                if (openId >= 0) {
                    BackHandler { openId = -1L }
                    MagazineReader(repo, openId, onClose = { openId = -1L })
                } else {
                    MagazineLibrary(repo, onOpen = { openId = it.id }, onBack = { finish() })
                }
            }
        }
    }
}

// --------------------------------------------------------------- biblioteca

@Composable
private fun MagazineLibrary(repo: GameRepository, onOpen: (Magazine) -> Unit, onBack: () -> Unit) {
    val list by repo.magazines.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var importing by remember { mutableStateOf(false) }
    var editMode by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Magazine?>(null) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            var ok = 0
            for (u in uris) {
                when (val r = repo.importUri(u)) {
                    is GameRepository.ImportResult.MagazineAdded -> ok++
                    is GameRepository.ImportResult.Error -> snackbar.showSnackbar(r.message)
                    else -> snackbar.showSnackbar("Eso no es un PDF; los juegos se añaden desde el catálogo")
                }
            }
            importing = false
            if (ok > 0) snackbar.showSnackbar(if (ok == 1) "Revista añadida" else "$ok revistas añadidas")
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = MaterialTheme.colorScheme.background) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            AppHeader("Revistas") {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
                IconButton(onClick = {
                    runCatching { pick.launch(arrayOf("application/pdf")) }
                }) { Icon(Icons.Default.Add, "Añadir revistas") }
                IconButton(onClick = { editMode = !editMode }) {
                    Icon(Icons.Default.Edit, "Editar", tint = if (editMode) ZxColors.Yellow else MaterialTheme.colorScheme.onSurface)
                }
            }
            if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (editMode) {
                Text("Toca una revista para cambiarle el nombre o borrarla.", Modifier.padding(horizontal = 16.dp), color = ZxColors.Yellow)
            }
            if (list.isEmpty()) {
                Text(
                    "Aún no tienes revistas. Añade tus PDF con el botón + o envíalos desde el móvil o el PC con «Desde otro dispositivo» (icono Wi-Fi del catálogo).",
                    Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(140.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(list, key = { it.id }) { m ->
                    val interaction = remember { MutableInteractionSource() }
                    val shape = RoundedCornerShape(10.dp)
                    Card(
                        onClick = { if (editMode) editing = m else onOpen(m) },
                        interactionSource = interaction,
                        shape = shape,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.focusHighlight(interaction, shape),
                    ) {
                        GameCover(m.title, m.thumbPath, Modifier.fillMaxWidth().aspectRatio(0.72f), contentScale = ContentScale.Crop)
                        Column(Modifier.padding(8.dp)) {
                            Text(m.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (m.lastPage > 0) "Pág. ${m.lastPage + 1} de ${m.pages}" else "${m.pages} páginas",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (m.pages > 0 && m.lastPage > 0) {
                                LinearProgressIndicator(
                                    progress = { (m.lastPage + 1f) / m.pages },
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { m ->
        var name by remember(m.id) { mutableStateOf(m.title) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Revista") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { repo.updateMagazine(m.copy(title = name.trim().ifBlank { m.title })) }
                    editing = null
                }) { Text("Guardar") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { scope.launch { repo.deleteMagazine(m) }; editing = null }) { Text("Borrar", color = ZxColors.Red) }
                    TextButton(onClick = { editing = null }) { Text("Cancelar") }
                }
            },
        )
    }
}

// ------------------------------------------------------------------- lector

@Composable
private fun MagazineReader(repo: GameRepository, id: Long, onClose: () -> Unit) {
    var magazine by remember { mutableStateOf<Magazine?>(null) }
    var pages by remember { mutableStateOf<PdfPages?>(null) }
    var error by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        val m = repo.magazine(id)
        magazine = m
        if (m == null) { onClose(); return@LaunchedEffect }
        pages = try { PdfPages(File(m.path)) } catch (e: Exception) { error = true; null }
    }
    DisposableEffect(id) {
        onDispose {
            pages?.close()
            kotlinx.coroutines.MainScope().launch { repo.refreshMagazines() }
        }
    }

    val m = magazine
    val p = pages
    Box(Modifier.fillMaxSize().background(Color(0xFF111111))) {
        when {
            error -> Text("No se pudo abrir esta revista.", Modifier.align(Alignment.Center), color = Color.White)
            m != null && p != null -> ReaderPager(repo, m, p, onClose)
        }
    }
}

@Composable
private fun ReaderPager(repo: GameRepository, m: Magazine, pdf: PdfPages, onClose: () -> Unit) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var doublePage by remember { mutableStateOf(landscape) }
    LaunchedEffect(landscape) { doublePage = landscape }

    // Con doble página: la portada sola y luego parejas (2-3, 4-5...), como una revista abierta
    val itemCount = if (doublePage) 1 + pdf.count / 2 else pdf.count
    fun pagesOf(item: Int): List<Int> =
        if (!doublePage) listOf(item)
        else if (item == 0) listOf(0)
        else listOf(2 * item - 1, 2 * item).filter { it < pdf.count }
    fun itemOf(page: Int): Int = if (!doublePage) page else (page + 1) / 2

    val pager = rememberPagerState(initialPage = itemOf(m.lastPage).coerceIn(0, (itemCount - 1).coerceAtLeast(0))) { itemCount }
    val scope = rememberCoroutineScope()
    var showBars by remember { mutableStateOf(true) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val focus = remember { FocusRequester() }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    fun clamp(o: Offset): Offset {
        val mx = (zoom - 1f) * boxSize.width / 2f
        val my = (zoom - 1f) * boxSize.height / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    // Al cambiar de modo, mantener la página
    LaunchedEffect(doublePage) {
        pager.scrollToPage(itemOf(m.lastPage.coerceAtMost(pdf.count - 1)).coerceIn(0, (itemCount - 1).coerceAtLeast(0)))
    }
    LaunchedEffect(pager.currentPage) {
        zoom = 1f
        offset = Offset.Zero
        delay(400)
        if (m.id >= 0) pagesOf(pager.currentPage).firstOrNull()?.let { repo.setMagazinePage(m.id, it) }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(showBars) {
        if (showBars) { delay(3500); showBars = false }
    }

    fun go(delta: Int) = scope.launch {
        pager.animateScrollToPage((pager.currentPage + delta).coerceIn(0, (itemCount - 1).coerceAtLeast(0)))
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it }
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val step = 120f
                when (e.key.nativeKeyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { if (zoom > 1.01f) offset = clamp(offset + Offset(-step, 0f)) else go(1); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { if (zoom > 1.01f) offset = clamp(offset + Offset(step, 0f)) else go(-1); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { if (zoom > 1.01f) offset = clamp(offset + Offset(0f, -step)) else showBars = true; true }
                    KeyEvent.KEYCODE_DPAD_UP -> { if (zoom > 1.01f) offset = clamp(offset + Offset(0f, step)) else showBars = true; true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                        zoom = when { zoom < 1.5f -> 2f; zoom < 2.5f -> 3f; else -> 1f }
                        offset = Offset.Zero
                        true
                    }
                    KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { go(10); true }
                    KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_REWIND -> { go(-10); true }
                    KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_Y -> { doublePage = !doublePage; true }
                    else -> false
                }
            },
    ) {
        val widthPx = boxSize.width.toFloat().coerceAtLeast(400f)

        HorizontalPager(
            state = pager,
            userScrollEnabled = zoom <= 1.01f,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { item ->
            val current = item == pager.currentPage
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { showBars = !showBars },
                            onDoubleTap = {
                                zoom = if (zoom > 1.01f) 1f else 2.5f
                                offset = Offset.Zero
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        // Pellizcar para ampliar; con zoom, arrastrar con un dedo mueve la página
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                val fingers = event.changes.count { it.pressed }
                                if (fingers >= 2 || zoom > 1.01f) {
                                    zoom = (zoom * event.calculateZoom()).coerceIn(1f, 5f)
                                    offset = clamp(offset + event.calculatePan())
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .graphicsLayer {
                        val z = if (current) zoom else 1f
                        scaleX = z
                        scaleY = z
                        translationX = if (current) offset.x else 0f
                        translationY = if (current) offset.y else 0f
                    },
                contentAlignment = Alignment.Center,
            ) {
                val indices = pagesOf(item)
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    indices.forEachIndexed { i, idx ->
                        PdfPageImage(
                            pdf, idx,
                            targetWidth = (widthPx / indices.size * if (current && zoom > 1.5f) minOf(zoom, 3f) else 1.3f).toInt(),
                            alignment = if (indices.size == 2) (if (i == 0) Alignment.CenterEnd else Alignment.CenterStart) else Alignment.Center,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }

        if (showBars) {
            Column(Modifier.fillMaxWidth().align(Alignment.TopCenter).background(Color(0xCC000000)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cerrar", tint = Color.White) }
                    Text(m.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    val shown = pagesOf(pager.currentPage)
                    Text(
                        (if (shown.size == 2) "${shown[0] + 1}-${shown[1] + 1}" else "${(shown.firstOrNull() ?: 0) + 1}") + " / ${pdf.count}",
                        color = Color.White,
                    )
                    TextButton(onClick = { doublePage = !doublePage }) {
                        Text(if (doublePage) "1 página" else "2 páginas", color = ZxColors.Cyan)
                    }
                }
            }
            Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(Color(0xCC000000)).padding(horizontal = 16.dp, vertical = 4.dp)) {
                var slider by remember(pager.currentPage, itemCount) { mutableFloatStateOf(pager.currentPage.toFloat()) }
                Slider(
                    value = slider,
                    onValueChange = { slider = it; showBars = true },
                    onValueChangeFinished = { scope.launch { pager.scrollToPage(slider.toInt()) } },
                    valueRange = 0f..(itemCount - 1).coerceAtLeast(1).toFloat(),
                )
                Text(
                    "Tocar: mostrar/ocultar · Doble toque o pellizcar: zoom · Mando: ◀ ▶ pasar página, OK zoom, L1/R1 saltar 10",
                    color = Color(0xFFBBBBBB),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun PdfPageImage(pdf: PdfPages, index: Int, targetWidth: Int, alignment: Alignment, modifier: Modifier) {
    var bmp by remember(index) { mutableStateOf<android.graphics.Bitmap?>(null) }
    // Redondeamos el ancho para no redibujar por cambios mínimos
    val w = ((targetWidth / 200) + 1) * 200
    LaunchedEffect(index, w) {
        pdf.render(index, w)?.let { bmp = it }
    }
    Box(modifier, contentAlignment = alignment) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = "Página ${index + 1}",
                contentScale = ContentScale.Fit,
                alignment = alignment,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text("Página ${index + 1}…", color = Color.Gray)
        }
    }
}

// --------------------------------------------------------------- documentos

@Composable
private fun DocumentViewer(repo: GameRepository, path: String, title: String, onClose: () -> Unit) {
    val ext = path.substringAfterLast('.', "").lowercase()
    when {
        ext == "pdf" -> {
            var pages by remember { mutableStateOf<PdfPages?>(null) }
            var error by remember { mutableStateOf(false) }
            LaunchedEffect(path) { pages = try { PdfPages(File(path)) } catch (e: Exception) { error = true; null } }
            DisposableEffect(path) { onDispose { pages?.close() } }
            Box(Modifier.fillMaxSize().background(Color(0xFF111111))) {
                val p = pages
                when {
                    error -> Text("No se pudo abrir el PDF.", Modifier.align(Alignment.Center), color = Color.White)
                    p != null -> ReaderPager(repo, Magazine(id = -1, title = title, path = path, pages = p.count), p, onClose)
                }
            }
        }
        ext in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp") -> ImageDocument(path, title, onClose)
        else -> TextDocument(path, title, onClose)
    }
}

@Composable
private fun DocTopBar(title: String, onClose: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xCC000000)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cerrar", tint = Color.White) }
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        actions()
    }
}

/** Instrucciones en texto: muchas vienen en codificaciones antiguas, probamos UTF-8 y si no Latin-1. */
private fun readOldText(file: File): String {
    val bytes = file.readBytes()
    val text = try {
        java.nio.charset.StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (e: Exception) {
        String(bytes, Charsets.ISO_8859_1)
    }
    val ext = file.extension.lowercase()
    return if (ext == "htm" || ext == "html") {
        @Suppress("DEPRECATION")
        android.text.Html.fromHtml(text).toString()
    } else text.replace("\r\n", "\n").replace('\r', '\n')
}

@Composable
private fun TextDocument(path: String, title: String, onClose: () -> Unit) {
    var text by remember { mutableStateOf<String?>(null) }
    var size by remember { mutableFloatStateOf(15f) }
    val scroll = androidx.compose.foundation.rememberScrollState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(path) {
        text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try { readOldText(File(path)) } catch (e: Exception) { "No se pudo leer el documento." }
        }
        runCatching { focus.requestFocus() }
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF0E0E10))) {
        DocTopBar(title, onClose) {
            TextButton(onClick = { size = (size - 2f).coerceAtLeast(9f) }) { Text("A-", color = ZxColors.Cyan) }
            TextButton(onClick = { size = (size + 2f).coerceAtMost(32f) }) { Text("A+", color = ZxColors.Cyan) }
        }
        Text(
            text ?: "Cargando…",
            color = Color(0xFFEDEDED),
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontSize = androidx.compose.ui.unit.TextUnit(size, androidx.compose.ui.unit.TextUnitType.Sp),
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key.nativeKeyCode) {
                        KeyEvent.KEYCODE_DPAD_DOWN -> { scope.launch { scroll.animateScrollBy(300f) }; true }
                        KeyEvent.KEYCODE_DPAD_UP -> { scope.launch { scroll.animateScrollBy(-300f) }; true }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> { size = (size + 2f).coerceAtMost(32f); true }
                        KeyEvent.KEYCODE_DPAD_LEFT -> { size = (size - 2f).coerceAtLeast(9f); true }
                        else -> false
                    }
                }
                .verticalScroll(scroll)
                .padding(16.dp),
        )
    }
}

@Composable
private fun ImageDocument(path: String, title: String, onClose: () -> Unit) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val bmp = remember(path) { android.graphics.BitmapFactory.decodeFile(path) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, z, _ ->
                            zoom = (zoom * z).coerceIn(1f, 6f)
                            offset = if (zoom <= 1.01f) Offset.Zero else offset + pan
                        }
                    }
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { zoom = if (zoom > 1.01f) 1f else 2.5f; offset = Offset.Zero }) }
                    .graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = offset.x; translationY = offset.y },
            )
        } else {
            Text("No se pudo abrir la imagen.", Modifier.align(Alignment.Center), color = Color.White)
        }
        Box(Modifier.align(Alignment.TopCenter)) { DocTopBar(title, onClose) }
    }
}
