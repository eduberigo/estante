package com.eduberigo.estante

import android.graphics.Bitmap
import android.graphics.RectF
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.hypot
import kotlin.math.roundToInt

private val nextKeys = setOf(
    KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_NEXT,
)
private val prevKeys = setOf(
    KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_MEDIA_PREVIOUS,
)

private val PenColors = listOf(0xFF000000, 0xFFD32F2F, 0xFF1565C0, 0xFF2E7D32).map { it.toInt() }
private val HlColors = listOf(0xFFFFEB3B, 0xFF76FF03, 0xFFFF4081, 0xFF40C4FF).map { it.toInt() }

/** Ferramentas de anotação. null = modo leitura. */
private enum class Tool(val label: String) {
    PEN("Caneta"), HL("Marca-texto"), CIRCLE("Círculo"), TEXT("Texto"), FINGER("Dedilhado"),
    ANCHOR("Âncora"), JUMP("Salto"), ERASER("Borracha"),
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReaderScreen(
    file: File,
    setlistName: String?,
    position: Pair<Int, Int>?,
    startAtEnd: Boolean,
    onNextSong: (() -> Unit)?,
    onPrevSong: ((atEnd: Boolean) -> Unit)?,
    onPlayed: (File, Int) -> Unit,
    lib: Library,
    autoCrop: Boolean,
    onAutoCrop: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val activity = ctx as MainActivity
    val nextSong by rememberUpdatedState(onNextSong)
    val prevSong by rememberUpdatedState(onPrevSong)
    val played by rememberUpdatedState(onPlayed)

    // Conta como "tocada" se a partitura ficou aberta pelo menos 30 segundos.
    DisposableEffect(file) {
        val start = System.currentTimeMillis()
        onDispose {
            val secs = ((System.currentTimeMillis() - start) / 1000).toInt()
            if (secs >= 30) played(file, secs)
        }
    }
    var reloadKey by remember { mutableStateOf(0) }
    val doc = remember(file, reloadKey) { openDoc(file) }
    DisposableEffect(doc) { onDispose { doc?.close() } }

    // Tela cheia e tela sempre ligada enquanto lê.
    DisposableEffect(Unit) {
        val window = activity.window
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val ctrl = WindowCompat.getInsetsController(window, window.decorView)
        ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctrl.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            ctrl.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    if (doc == null) {
        Column(
            Modifier.fillMaxSize().background(Color.Black).padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Não foi possível abrir este arquivo.", color = Color.White)
            Text(file.name, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onClose) { Text("Voltar") }
        }
        return
    }

    val count = doc.pageCount.coerceAtLeast(1)
    val pager = rememberPagerState(
        initialPage = if (startAtEnd) count - 1 else doc.startPage.coerceIn(0, count - 1),
        pageCount = { count },
    )
    val scope = rememberCoroutineScope()
    var showBars by remember { mutableStateOf(false) }
    var savePdf by remember { mutableStateOf(false) }
    val imageFiles = doc.pageFiles

    // ---------- anotações ----------
    /** Para cada página do leitor: em que arquivo e em que página desse arquivo ficam as anotações. */
    fun keyOf(i: Int): Pair<String, Int> =
        if (imageFiles != null) (imageFiles.getOrNull(i)?.absolutePath ?: file.absolutePath) to 0 else file.absolutePath to i

    val notes = remember(file) { mutableStateMapOf<String, FileNotes>() }
    val undo = remember(file) { mutableStateListOf<Pair<String, FileNotes>>() }
    LaunchedEffect(file, reloadKey) {
        val paths = imageFiles?.map { it.absolutePath } ?: listOf(file.absolutePath)
        val loaded = withContext(Dispatchers.IO) { paths.associateWith { AnnStore.load(ctx, it) } }
        notes.putAll(loaded)
    }

    fun update(path: String, f: (FileNotes) -> FileNotes) {
        val old = notes[path] ?: FileNotes()
        val new = f(old)
        undo.add(path to old)
        if (undo.size > 60) undo.removeAt(0)
        notes[path] = new
        scope.launch(Dispatchers.IO) { AnnStore.save(ctx, path, new) }
    }

    /** Âncoras de todo o documento, com a página do leitor onde estão. */
    val anchors: List<Pair<Int, Anchor>> = if (imageFiles != null) {
        imageFiles.flatMapIndexed { j, f -> notes[f.absolutePath]?.anchors.orEmpty().map { j to it } }
    } else {
        notes[file.absolutePath]?.anchors.orEmpty().map { it.page to it }
    }.sortedWith(compareBy({ it.first }, { it.second.y }))

    var tool by remember { mutableStateOf<Tool?>(null) }
    var penColor by remember { mutableIntStateOf(PenColors[1]) }
    var hlColor by remember { mutableIntStateOf(HlColors[0]) }
    var finger by remember { mutableIntStateOf(1) }
    var pendingText by remember { mutableStateOf<Triple<Int, Offset, String?>?>(null) }
    var pendingAnchor by remember { mutableStateOf<Pair<Int, Offset>?>(null) }
    var pendingJump by remember { mutableStateOf<Pair<Int, Offset>?>(null) }
    var anchorList by remember { mutableStateOf(false) }
    var cropEdit by remember { mutableStateOf(false) }
    var cropFiles by remember { mutableStateOf<Pair<List<File>, (File, Int) -> RectF?>?>(null) }
    val volumes = remember { Fs.volumes(ctx) }
    val metro = remember { Metronome() }
    DisposableEffect(Unit) { onDispose { metro.stop() } }
    var metroPanel by remember { mutableStateOf(false) }
    var imagePanel by remember { mutableStateOf(false) }
    var applyFile by remember { mutableStateOf<String?>(null) }

    fun goTo(page: Int) {
        val t = page.coerceIn(0, count - 1)
        scope.launch { pager.scrollToPage(t) }
    }

    fun jumpTo(label: String) {
        val a = anchors.firstOrNull { it.second.label.equals(label, ignoreCase = true) }
        if (a != null) goTo(a.first)
    }

    val turn: (Int) -> Unit = remember(pager, count) {
        { delta ->
            val raw = pager.currentPage + delta
            when {
                raw > count - 1 && nextSong != null -> nextSong?.invoke()
                raw < 0 && prevSong != null -> prevSong?.invoke(true)
                else -> {
                    val target = raw.coerceIn(0, count - 1)
                    if (target != pager.currentPage) scope.launch { pager.scrollToPage(target) }
                }
            }
        }
    }

    DisposableEffect(turn) {
        activity.keyHandler = { code, down ->
            when (code) {
                in nextKeys -> { if (down) turn(1); true }
                in prevKeys -> { if (down) turn(-1); true }
                else -> false
            }
        }
        onDispose { activity.keyHandler = null }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            userScrollEnabled = tool == null,
            key = { it },
        ) { index ->
            val (path, pg) = keyOf(index)
            val n = notes[path]
            PageView(
                doc = doc,
                index = index,
                crop = autoCrop,
                look = n?.look ?: Look(),
                manual = n?.cropFor(pg),
                anns = n?.pages?.get(pg).orEmpty(),
                anchors = n?.anchors.orEmpty().filter { it.page == pg },
                jumps = n?.jumps.orEmpty().filter { it.page == pg },
                tool = tool,
                drawColor = if (tool == Tool.HL) hlColor else penColor,
                onTap = { frac ->
                    when {
                        frac < 0.3f -> turn(-1)
                        frac > 0.7f -> turn(1)
                        else -> showBars = !showBars
                    }
                },
                onJump = { jumpTo(it) },
                onCommit = { a -> update(path) { it.add(pg, a) } },
                onToolTap = { pt, view, map ->
                    when (tool) {
                        Tool.TEXT -> pendingText = Triple(index, pt, null)
                        Tool.FINGER -> update(path) {
                            it.add(pg, Ann("finger", penColor, 0.03f, listOf(pt.x, pt.y), finger.toString()))
                        }
                        Tool.ANCHOR -> pendingAnchor = index to pt
                        Tool.JUMP -> pendingJump = index to pt
                        Tool.ERASER -> {
                            val cur = notes[path] ?: FileNotes()
                            val list = cur.pages[pg].orEmpty()
                            val near = list.withIndex().minByOrNull { AnnDraw.distance(map, it.value, view) }
                            val tagNear = { x: Float, y: Float -> map.toView(x, y).let { hypot(it.x - view.x, it.y - view.y) } }
                            val anc = cur.anchors.filter { it.page == pg }.minByOrNull { tagNear(it.x, it.y) }
                            val jmp = cur.jumps.filter { it.page == pg }.minByOrNull { tagNear(it.x, it.y) }
                            when {
                                anc != null && tagNear(anc.x, anc.y) < 50f -> update(path) { it.copy(anchors = it.anchors - anc) }
                                jmp != null && tagNear(jmp.x, jmp.y) < 50f -> update(path) { it.copy(jumps = it.jumps - jmp) }
                                near != null && AnnDraw.distance(map, near.value, view) < 40f -> update(path) {
                                    it.copy(pages = it.pages + (pg to list.filterIndexed { k, _ -> k != near.index }))
                                }
                            }
                        }
                        else -> Unit
                    }
                },
            )
        }

        val clock by produceState(nowText()) {
            while (true) {
                value = nowText()
                kotlinx.coroutines.delay(1000L * (60 - java.util.Calendar.getInstance().get(java.util.Calendar.SECOND)).coerceAtLeast(1))
            }
        }

        // Relógio discreto, sempre visível.
        if (!showBars && tool == null) {
            Text(
                if (position != null) "${position.first + 1}/${position.second} • $clock" else clock,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .displayCutoutPadding()
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }

        // Atalhos para as âncoras (Intro, S, Coda…), discretos no canto de baixo.
        if (!showBars && tool == null && anchors.isNotEmpty()) {
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                anchors.forEach { (pg, a) ->
                    Text(
                        a.label,
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(AnnDraw.AnchorColor.copy(alpha = if (pg == pager.currentPage) 0.85f else 0.45f))
                            .clickable { goTo(pg) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }

        if (showBars && tool == null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Color.Black.copy(alpha = 0.8f))
                    .statusBarsPadding()
                    .displayCutoutPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Fechar", tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            imageFiles?.getOrNull(pager.currentPage)?.nameWithoutExtension ?: file.nameWithoutExtension,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (position != null) {
                            Text(
                                "${setlistName ?: "Setlist"} • música ${position.first + 1} de ${position.second}",
                                color = Color.LightGray,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        }
                    }
                    if (position != null) {
                        IconButton(enabled = onPrevSong != null, onClick = { onPrevSong?.invoke(false) }) {
                            Text("⏮", color = if (onPrevSong != null) Color.White else Color.DarkGray, style = MaterialTheme.typography.titleLarge)
                        }
                        IconButton(enabled = onNextSong != null, onClick = { onNextSong?.invoke() }) {
                            Text("⏭", color = if (onNextSong != null) Color.White else Color.DarkGray, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                    Text(clock, color = Color.White, modifier = Modifier.padding(horizontal = 8.dp))
                    Text(
                        "${pager.currentPage + 1} / $count",
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BarButton("✎ Anotar", true) { tool = Tool.PEN; showBars = false }
                    BarButton("⚓ Âncoras${if (anchors.isNotEmpty()) " (${anchors.size})" else ""}", true) { anchorList = true }
                    BarButton("◐ Imagem", true) { imagePanel = true; showBars = false }
                    BarButton("✂ Recortar", true) { cropEdit = true; showBars = false }
                    BarButton("♩ Metrônomo", true) {
                        val n = notes[file.absolutePath]
                        if (!metro.running.value) {
                            metro.bpm = n?.bpm ?: Meta.bpmOf(file.nameWithoutExtension) ?: metro.bpm
                            metro.beats = n?.beats ?: metro.beats
                        }
                        metroPanel = true
                        showBars = false
                    }
                    IconButton(onClick = { onAutoCrop(!autoCrop) }) {
                        Icon(
                            painterResource(R.drawable.ic_crop),
                            if (autoCrop) "Desligar recorte automático" else "Ligar recorte automático",
                            tint = if (autoCrop) Color(0xFF8AB4F8) else Color.Gray,
                        )
                    }
                    if (imageFiles != null) BarButton("Salvar PDF", true) { savePdf = true }
                }
            }
            if (count > 1) {
                var pos by remember(pager.currentPage) { mutableFloatStateOf(pager.currentPage.toFloat()) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(Color.Black.copy(alpha = 0.75f))
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                ) {
                    Slider(
                        value = pos,
                        onValueChange = { pos = it },
                        onValueChangeFinished = { goTo(pos.roundToInt()) },
                        valueRange = 0f..(count - 1).toFloat(),
                    )
                }
            }
        }

        val t = tool
        if (t != null) {
            AnnotToolbar(
                tool = t,
                onTool = { tool = it },
                penColor = penColor,
                onPenColor = { penColor = it },
                hlColor = hlColor,
                onHlColor = { hlColor = it },
                finger = finger,
                onFinger = { finger = it },
                canUndo = undo.isNotEmpty(),
                onUndo = {
                    val (p, old) = undo.removeAt(undo.lastIndex)
                    notes[p] = old
                    scope.launch(Dispatchers.IO) { AnnStore.save(ctx, p, old) }
                },
                page = "${pager.currentPage + 1}/$count",
                onPrevPage = { goTo(pager.currentPage - 1) },
                onNextPage = { goTo(pager.currentPage + 1) },
                onDone = { tool = null },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        MetroIndicator(metro, Modifier.align(Alignment.TopStart))

        if (imagePanel) {
            val (ipath, _) = keyOf(pager.currentPage)
            ImagePanel(
                look = notes[ipath]?.look ?: Look(),
                onLook = { lk -> update(ipath) { it.copy(look = if (lk.isDefault) null else lk) } },
                onApplyFile = { applyFile = ipath },
                onClose = { imagePanel = false },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        applyFile?.let { p ->
            PrepareFilesDialog(listOf(p), volumes, lib, autoCrop, onRelink = { }) {
                applyFile = null
                notes.clear()
                reloadKey++
            }
        }

        if (metroPanel) {
            MetronomePanel(
                metro = metro,
                modifier = Modifier.align(Alignment.BottomCenter),
                onClose = {
                    metroPanel = false
                    val bpm = metro.bpm
                    val beats = metro.beats
                    update(file.absolutePath) { it.copy(bpm = bpm, beats = beats) }
                },
            )
        }

        if (cropEdit) {
            val cur = pager.currentPage
            val (cpath, cpg) = keyOf(cur)
            CropEditor(
                doc = doc,
                index = cur,
                initial = notes[cpath]?.cropFor(cpg),
                onCancel = { cropEdit = false },
            ) { rect, all, toFile ->
                cropEdit = false
                val targets: List<String> = if (imageFiles != null && all) imageFiles.map { it.absolutePath } else listOf(cpath)
                fun clearCrops(p: String) = update(p) { it.copy(crops = emptyMap()) }
                if (!toFile) {
                    targets.forEach { p ->
                        update(p) { nn ->
                            val key = if (imageFiles != null) 0 else if (all) -1 else cpg
                            val base = if (all) emptyMap() else nn.crops
                            nn.copy(crops = if (rect == null) base - key else base + (key to listOf(rect.left, rect.top, rect.right, rect.bottom)))
                        }
                    }
                } else if (rect != null) {
                    targets.forEach { clearCrops(it) }
                    val files = targets.map { File(it) }
                    val one = RectF(rect)
                    cropFiles = files to { f: File, i: Int ->
                        if (all || imageFiles != null) one else if (f.absolutePath == file.absolutePath && i == cpg) one else null
                    }
                }
            }
        }

        cropFiles?.let { (fs, rf) ->
            CropFileDialog(fs, rf, volumes, lib) { _ ->
                cropFiles = null
                notes.clear()
                reloadKey++
            }
        }

        if (savePdf && imageFiles != null) {
            SaveImagesPdfDialog(imageFiles, pager.currentPage, lib) { savePdf = false }
        }

        pendingText?.let { (idx, pt, _) ->
            TextInputDialog("Texto na partitura", "", onDismiss = { pendingText = null }) { txt ->
                val (path, pg) = keyOf(idx)
                update(path) { it.add(pg, Ann("text", penColor, 0.032f, listOf(pt.x, pt.y), txt)) }
                pendingText = null
            }
        }

        pendingAnchor?.let { (idx, pt) ->
            AnchorLabelDialog(
                existing = anchors.map { it.second.label },
                onDismiss = { pendingAnchor = null },
            ) { label ->
                val (path, pg) = keyOf(idx)
                // O mesmo rótulo só pode existir uma vez: a nova posição substitui a antiga.
                anchors.filter { it.second.label.equals(label, true) }.forEach { (p, a) ->
                    val (op, _) = keyOf(p)
                    update(op) { it.copy(anchors = it.anchors - a) }
                }
                update(path) { it.copy(anchors = it.anchors + Anchor(label, pg, pt.x, pt.y)) }
                pendingAnchor = null
            }
        }

        pendingJump?.let { (idx, pt) ->
            JumpTargetDialog(
                labels = anchors.map { it.second.label },
                onDismiss = { pendingJump = null },
            ) { target ->
                val (path, pg) = keyOf(idx)
                update(path) { it.copy(jumps = it.jumps + Jump(target, pg, pt.x, pt.y)) }
                pendingJump = null
            }
        }

        if (anchorList) {
            AlertDialog(
                onDismissRequest = { anchorList = false },
                title = { Text("Âncoras") },
                text = {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        if (anchors.isEmpty()) {
                            Text(
                                "Nenhuma âncora ainda. Em ✎ Anotar, use a ferramenta Âncora e toque no ponto da partitura " +
                                    "(Intro, S, Coda…). Depois, com a ferramenta Salto, toque no sinal \"To Coda\" e escolha o destino: " +
                                    "na hora de tocar, um toque no sinal leva direto até lá."
                            )
                        }
                        anchors.forEach { (pg, a) ->
                            Row(
                                Modifier.fillMaxWidth().clickable { anchorList = false; goTo(pg) }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(a.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                Text("página ${pg + 1}", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { anchorList = false }) { Text("Fechar") } },
            )
        }
    }
}

@Composable
private fun BarButton(label: String, on: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(label, color = if (on) Color(0xFF8AB4F8) else Color.Gray)
    }
}

/** Barra de ferramentas de anotação, embaixo da tela. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnnotToolbar(
    tool: Tool,
    onTool: (Tool) -> Unit,
    penColor: Int,
    onPenColor: (Int) -> Unit,
    hlColor: Int,
    onHlColor: (Int) -> Unit,
    finger: Int,
    onFinger: (Int) -> Unit,
    canUndo: Boolean,
    onUndo: () -> Unit,
    page: String,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier,
) {
    Surface(
        modifier.fillMaxWidth().navigationBarsPadding(),
        color = Color(0xF0202124),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Tool.entries.forEach { t ->
                    val sel = t == tool
                    Text(
                        t.label,
                        color = if (sel) Color.Black else Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (sel) Color(0xFF8AB4F8) else Color(0xFF3C4043))
                            .clickable { onTool(t) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    when (tool) {
                        Tool.HL -> HlColors.forEach { c -> ColorDot(c, c == hlColor) { onHlColor(c) } }
                        Tool.ANCHOR -> Hint("Toque no ponto: Intro, S, Coda…")
                        Tool.JUMP -> Hint("Toque no sinal (ex.: To Coda) e escolha a âncora de destino")
                        Tool.ERASER -> Hint("Toque na anotação para apagar")
                        else -> {
                            PenColors.forEach { c -> ColorDot(c, c == penColor) { onPenColor(c) } }
                            if (tool == Tool.FINGER) {
                                Spacer(Modifier.width(8.dp))
                                (1..5).forEach { n ->
                                    Text(
                                        n.toString(),
                                        color = if (n == finger) Color.Black else Color.White,
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier
                                            .padding(2.dp)
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(if (n == finger) Color(0xFF8AB4F8) else Color(0xFF3C4043))
                                            .clickable { onFinger(n) }
                                            .padding(top = 8.dp),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    )
                                }
                            }
                            if (tool == Tool.TEXT) Hint("Toque onde o texto vai ficar")
                        }
                    }
                }
                TextButton(onClick = onPrevPage) { Text("◀", color = Color.White) }
                Text(page, color = Color.White, style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = onNextPage) { Text("▶", color = Color.White) }
                TextButton(enabled = canUndo, onClick = onUndo) { Text("Desfazer", color = if (canUndo) Color.White else Color.Gray) }
                Button(onClick = onDone) { Text("Concluir") }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Color.LightGray, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 6.dp))
}

@Composable
private fun ColorDot(c: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(4.dp)
            .size(32.dp)
            .clip(CircleShape)
            .background(Color(c))
            .border(if (selected) 3.dp else 1.dp, if (selected) Color.White else Color.Gray, CircleShape)
            .clickable(onClick = onClick)
    )
}

@Composable
private fun TextInputDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, placeholder = { Text("Ex.: rit., 2ª vez, pedal") })
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onOk(text.trim()) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnchorLabelDialog(existing: List<String>, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var custom by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nome da âncora") },
        text = {
            Column {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AnchorPresets.forEach { l ->
                        val used = existing.any { it.equals(l, true) }
                        Text(
                            if (used) "$l ↺" else l,
                            modifier = Modifier
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .clickable { onOk(l) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
                OutlinedTextField(
                    value = custom, onValueChange = { custom = it },
                    label = { Text("Outro nome") }, singleLine = true,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "↺ = já existe; a nova posição substitui a anterior.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(enabled = custom.isNotBlank(), onClick = { onOk(custom.trim()) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun JumpTargetDialog(labels: List<String>, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Este sinal leva para…") },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                if (labels.isEmpty()) {
                    Text("Ainda não há âncoras neste arquivo. Crie primeiro o destino (ex.: Coda) com a ferramenta Âncora.")
                }
                labels.forEach { l ->
                    Text(
                        "→ $l",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth().clickable { onOk(l) }.padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/**
 * Uma página: desenhada no tamanho da tela, com as anotações por cima.
 * Leitura: pinça amplia, toque nas bordas vira, toque num salto (→ Coda) pula.
 * Anotação: um dedo (ou a S Pen) desenha.
 */
@Composable
private fun PageView(
    doc: Doc,
    index: Int,
    crop: Boolean,
    look: Look,
    manual: RectF?,
    anns: List<Ann>,
    anchors: List<Anchor>,
    jumps: List<Jump>,
    tool: Tool?,
    drawColor: Int,
    onTap: (Float) -> Unit,
    onJump: (String) -> Unit,
    onCommit: (Ann) -> Unit,
    onToolTap: (page: Offset, view: Offset, map: PageMap) -> Unit,
) {
    val tapHandler by rememberUpdatedState(onTap)
    val jumpHandler by rememberUpdatedState(onJump)
    val commit by rememberUpdatedState(onCommit)
    val toolTap by rememberUpdatedState(onToolTap)
    val jumpsNow by rememberUpdatedState(jumps)
    val tm = rememberTextMeasurer()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wPx = constraints.maxWidth
        val hPx = constraints.maxHeight
        val rendered by produceState<Pair<Bitmap, RectF>?>(null, doc, index, wPx, hPx, crop, look, manual) {
            // Um pouco acima do tamanho da tela, para o zoom não borrar tanto.
            val b = doc.render(index, (wPx * 1.3f).toInt(), (hPx * 1.3f).toInt(), crop, look, manual)
            value = b?.let { it to RectF(doc.shownRect(index)) }
        }
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        LaunchedEffect(tool) { if (tool != null) { scale = 1f; offset = Offset.Zero } }

        val r = rendered
        val map = remember(r, wPx, hPx) {
            r?.let { PageMap(Size(wPx.toFloat(), hPx.toFloat()), it.first.width, it.first.height, it.second) }
        }
        val mapNow by rememberUpdatedState(map)

        // Traço em andamento (coordenadas da página).
        val live = remember { mutableStateListOf<Float>() }

        /** Toque na tela → ponto sem o zoom aplicado. */
        fun unzoom(p: Offset): Offset {
            val c = Offset(wPx / 2f, hPx / 2f)
            return c + (p - c - offset) / scale
        }

        val gestures = if (tool == null) {
            Modifier
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { p ->
                        val m = mapNow
                        if (m != null) {
                            val q = unzoom(p)
                            val hit = jumpsNow.firstOrNull { j ->
                                val v = m.toView(j.x, j.y)
                                hypot(v.x - q.x, v.y - q.y) < 60f
                            }
                            if (hit != null) {
                                jumpHandler(hit.target)
                                return@detectTapGestures
                            }
                        }
                        tapHandler(p.x / size.width)
                    })
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val fingers = event.changes.count { it.pressed }
                            if (fingers >= 2 || scale > 1.01f) {
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val centroid = event.calculateCentroid(useCurrent = true)
                                var newScale = (scale * zoom).coerceIn(1f, 5f)
                                val center = Offset(size.width / 2f, size.height / 2f)
                                var t = offset
                                if (centroid != Offset.Unspecified) {
                                    val g = centroid - center
                                    t = g - (g - t) * (newScale / scale)
                                }
                                t += pan
                                if (newScale < 1.02f) {
                                    newScale = 1f
                                    t = Offset.Zero
                                }
                                val maxX = (newScale - 1f) * size.width / 2f
                                val maxY = (newScale - 1f) * size.height / 2f
                                offset = Offset(t.x.coerceIn(-maxX, maxX), t.y.coerceIn(-maxY, maxY))
                                scale = newScale
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
        } else if (tool == Tool.PEN || tool == Tool.HL || tool == Tool.CIRCLE) {
            Modifier.pointerInput(tool, drawColor) {
                detectDragGestures(
                    onDragStart = { p ->
                        val m = mapNow ?: return@detectDragGestures
                        val q = m.toPage(p)
                        live.clear()
                        live.add(q.x); live.add(q.y)
                        if (tool == Tool.CIRCLE) { live.add(q.x); live.add(q.y) }
                    },
                    onDragEnd = {
                        if (live.size >= 2) {
                            val pts = live.toList()
                            val a = when (tool) {
                                Tool.PEN -> Ann("pen", drawColor, 0.0035f, pts)
                                Tool.HL -> Ann("hl", drawColor, 0.02f, pts)
                                else -> Ann("circle", drawColor, 0.003f, pts.take(4))
                            }
                            commit(a)
                        }
                        live.clear()
                    },
                    onDragCancel = { live.clear() },
                ) { change, _ ->
                    val m = mapNow ?: return@detectDragGestures
                    change.consume()
                    val q = m.toPage(change.position)
                    if (tool == Tool.CIRCLE) {
                        if (live.size >= 4) { live[2] = q.x; live[3] = q.y }
                    } else {
                        live.add(q.x); live.add(q.y)
                    }
                }
            }
        } else {
            Modifier.pointerInput(tool) {
                detectTapGestures(onTap = { p ->
                    val m = mapNow ?: return@detectTapGestures
                    toolTap(m.toPage(p), p, m)
                })
            }
        }

        Box(Modifier.fillMaxSize().then(gestures), contentAlignment = Alignment.Center) {
            if (r != null && map != null) {
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                ) {
                    Image(
                        bitmap = r.first.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Canvas(Modifier.fillMaxSize()) {
                        AnnDraw.draw(this, map, anns, tm)
                        if (live.size >= 2) {
                            val preview = when (tool) {
                                Tool.HL -> Ann("hl", drawColor, 0.02f, live.toList())
                                Tool.CIRCLE -> if (live.size >= 4) Ann("circle", drawColor, 0.003f, live.take(4)) else null
                                else -> Ann("pen", drawColor, 0.0035f, live.toList())
                            }
                            if (preview != null) AnnDraw.draw(this, map, listOf(preview), tm)
                        }
                        anchors.forEach { AnnDraw.tag(this, map, it.x, it.y, "⚓ " + it.label, AnnDraw.AnchorColor, tm) }
                        jumps.forEach { AnnDraw.tag(this, map, it.x, it.y, "→ " + it.target, AnnDraw.JumpColor, tm) }
                    }
                }
            } else {
                CircularProgressIndicator(color = Color.DarkGray)
            }
        }
    }
}

private val clockFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale("pt", "BR"))
private fun nowText(): String = clockFmt.format(java.util.Date())


/** Indicador discreto no canto: pisca a cada tempo (maior e laranja no 1). Toque para parar. */
@Composable
private fun MetroIndicator(metro: Metronome, modifier: Modifier) {
    val beat by metro.beat.collectAsState()
    val running by metro.running.collectAsState()
    val tick by metro.tick.collectAsState()
    var flash by remember { mutableStateOf(false) }
    // Pisca a cada batida: acende e apaga antes da próxima.
    LaunchedEffect(tick) {
        if (!running) return@LaunchedEffect
        flash = true
        kotlinx.coroutines.delay((30000L / metro.bpm.coerceAtLeast(30)).coerceAtMost(180L))
        flash = false
    }
    if (!running) return
    val first = beat == 0
    Box(
        modifier
            .displayCutoutPadding()
            .statusBarsPadding()
            .padding(10.dp)
            .size(36.dp)
            .clickable { metro.stop() },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(if (first) 30.dp else 24.dp)
                .clip(CircleShape)
                .background(
                    when {
                        flash && first -> Color(0xFFFF9800)
                        flash -> Color.White
                        else -> Color.White.copy(alpha = 0.12f)
                    }
                )
                .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)
        )
        if (beat >= 0) {
            Text(
                (beat + 1).toString(),
                color = if (flash) Color.Black else Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** Painel do metrônomo: BPM, compasso, som, tocar, contagem de 2 compassos. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetronomePanel(metro: Metronome, modifier: Modifier, onClose: () -> Unit) {
    val running by metro.running.collectAsState()
    var bpm by remember { mutableIntStateOf(metro.bpm) }
    var beats by remember { mutableIntStateOf(metro.beats) }
    var sound by remember { mutableStateOf(metro.sound) }
    val taps = remember { mutableStateListOf<Long>() }
    fun setBpm(v: Int) {
        bpm = v.coerceIn(30, 300)
        metro.bpm = bpm
    }
    Surface(modifier.fillMaxWidth().navigationBarsPadding(), color = Color(0xF0202124)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("♩ = $bpm", color = Color.White, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                listOf(-5, -1, 1, 5).forEach { d ->
                    TextButton(onClick = { setBpm(bpm + d) }) { Text(if (d > 0) "+$d" else "$d", color = Color.White) }
                }
                OutlinedButtonDark("Tap") {
                    val now = System.currentTimeMillis()
                    if (taps.isNotEmpty() && now - taps.last() > 2000) taps.clear()
                    taps.add(now)
                    if (taps.size > 5) taps.removeAt(0)
                    if (taps.size >= 2) {
                        val avg = (taps.last() - taps.first()).toDouble() / (taps.size - 1)
                        setBpm((60000.0 / avg).roundToInt())
                    }
                }
            }
            Slider(value = bpm.toFloat(), onValueChange = { setBpm(it.roundToInt()) }, valueRange = 30f..260f)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Text("Compasso:", color = Color.LightGray, modifier = Modifier.padding(end = 6.dp))
                listOf(2, 3, 4, 6).forEach { b ->
                    Text(
                        "$b/${if (b == 6) 8 else 4}",
                        color = if (b == beats) Color.Black else Color.White,
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (b == beats) Color(0xFF8AB4F8) else Color(0xFF3C4043))
                            .clickable { beats = b; metro.beats = b }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                Text(
                    if (sound) "Som: ligado" else "Som: desligado (só visual)",
                    color = Color.White,
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF3C4043))
                        .clickable { sound = !sound; metro.sound = sound }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { if (running) metro.stop() else metro.start() }) {
                    Text(if (running) "■ Parar" else "▶ Tocar")
                }
                OutlinedButtonDark("Contagem: 2 compassos") { metro.start(countInBars = 2) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Fechar", color = Color.White) }
            }
            Text(
                "A contagem toca 2 compassos e para sozinha. O indicador fica no canto de cima; toque nele para parar.",
                color = Color.Gray,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun OutlinedButtonDark(label: String, onClick: () -> Unit) {
    androidx.compose.material3.OutlinedButton(onClick = onClick) { Text(label, color = Color.White) }
}


/**
 * Ajustes de imagem desta partitura. Ficam salvos por arquivo: ao voltar, a partitura aparece igual.
 * "Gravar no arquivo" deixa tudo pronto antes do show.
 */
@Composable
private fun ImagePanel(
    look: Look,
    onLook: (Look) -> Unit,
    onApplyFile: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    // Valores locais enquanto arrasta; salva ao soltar (cada render custa).
    var paper by remember(look) { mutableFloatStateOf(look.paper) }
    var ink by remember(look) { mutableFloatStateOf(look.ink) }
    var sharp by remember(look) { mutableFloatStateOf(look.sharp) }
    Surface(modifier.fillMaxWidth().navigationBarsPadding(), color = Color(0xF0202124)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ajustes de imagem desta partitura", color = Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Fechar", color = Color.White) }
            }
            Row(
                Modifier.fillMaxWidth().clickable { onLook(look.copy(deskew = !look.deskew)) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Switch(checked = look.deskew, onCheckedChange = { onLook(look.copy(deskew = it)) })
                Text(
                    "  Endireitar página torta" + if (look.deskew) " (ligado)" else " (desligado)",
                    color = Color.White,
                )
            }
            SliderRow("Fundo", "normal", "mais branco", paper, 0f..1f, { paper = it }) { onLook(look.copy(paper = paper)) }
            SliderRow("Preto", "mais claro", "mais escuro", ink, -1f..1f, { ink = it }) { onLook(look.copy(ink = ink)) }
            SliderRow("Nitidez", "mais suave", "mais nítida", sharp, -1f..1f, { sharp = it }) { onLook(look.copy(sharp = sharp)) }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButtonDark("Atalho: Legível") { onLook(Look.LEGIVEL.copy(deskew = look.deskew)) }
                OutlinedButtonDark("Restaurar") { onLook(Look(deskew = look.deskew)) }
                Button(onClick = onApplyFile) { Text("Gravar no arquivo…") }
            }
            Text(
                "Os ajustes ficam salvos só para esta partitura. Curvatura de página (livro aberto) não é corrigida.",
                color = Color.Gray,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun SliderRow(
    label: String, left: String, right: String, value: Float, range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit, onDone: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, modifier = Modifier.width(64.dp))
        Text(left, color = Color.Gray, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(70.dp))
        Slider(
            value = value, onValueChange = onChange, onValueChangeFinished = onDone,
            valueRange = range, modifier = Modifier.weight(1f),
        )
        Text(right, color = Color.Gray, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(78.dp).padding(start = 6.dp))
    }
}
