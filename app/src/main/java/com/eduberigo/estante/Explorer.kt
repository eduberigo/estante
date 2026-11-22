package com.eduberigo.estante

import android.graphics.Bitmap
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

internal val FavColor = Color(0xFFFFB300)

private val ThumbNames = listOf("Pequenas", "Médias", "Grandes", "Enormes")
private val GridMin = listOf(90, 120, 170, 240)
private val GridPx = listOf(240, 320, 460, 640)
private val ListW = listOf(36, 44, 64, 96)
private val ListPx = listOf(100, 130, 200, 300)

/** Estado do explorador. Fica vivo enquanto o leitor está aberto, para voltar ao mesmo ponto. */
class ExplorerState(private val prefs: Prefs) {
    /** true = tela Início (pastas escolhidas + favoritos); false = dentro de uma pasta. */
    var atHome by mutableStateOf(true)
        private set
    var dir by mutableStateOf(startDir())
        private set
    var entries by mutableStateOf<List<Entry>>(emptyList())
    var loading by mutableStateOf(false)
    var selected by mutableStateOf<Entry?>(null)
    var grid by mutableStateOf(prefs.grid)
        private set

    var pinned by mutableStateOf(prefs.pinned)
        private set
    var favorites by mutableStateOf(prefs.favorites)
        private set

    var thumbSize by mutableIntStateOf(prefs.thumbSize)
        private set

    fun updateThumbSize(v: Int) {
        thumbSize = v.coerceIn(0, 3)
        prefs.thumbSize = thumbSize
    }

    var autoCrop by mutableStateOf(prefs.autoCrop)
        private set

    var textScale by mutableFloatStateOf(prefs.textScale)
        private set

    fun updateTextScale(v: Float) {
        textScale = v.coerceIn(0.8f, 1.6f)
        prefs.textScale = textScale
    }

    /** Seleção múltipla (caminhos). Vazia = modo normal. */
    var selection by mutableStateOf<Set<String>>(emptySet())

    fun toggleSel(p: String) {
        selection = if (p in selection) selection - p else selection + p
    }

    fun isSel(p: String) = selected?.path == p || p in selection

    /** Itens "recortados/copiados" aguardando colar numa pasta. */
    data class Clip(val paths: List<String>, val move: Boolean)

    var clip by mutableStateOf<Clip?>(null)

    /** Depois de mover: favoritos e pastas do Início seguem os caminhos novos. */
    fun relink(moved: Map<String, String>) {
        if (moved.isEmpty()) return
        fun map(p: String): String {
            moved[p]?.let { return it }
            for ((a, b) in moved) if (p.startsWith("$a/")) return b + p.removePrefix(a)
            return p
        }
        favorites = favorites.map(::map)
        prefs.favorites = favorites
        pinned = pinned.map(::map)
        prefs.pinned = pinned
    }
    var showPreviewPref by mutableStateOf(prefs.showPreview)
        private set
    var splitPortrait by mutableFloatStateOf(prefs.splitPortrait)
    var splitLandscape by mutableFloatStateOf(prefs.splitLandscape)

    var searching by mutableStateOf(false)
    var query by mutableStateOf("")
    var results by mutableStateOf<List<Entry>>(emptyList())
    var indexing by mutableStateOf<Int?>(null)
    var indexVersion by mutableIntStateOf(0)
    var reloadTick by mutableIntStateOf(0)

    /** Setlist aberto (id de um manual, ou "auto:..."); null = pasta ou Início. */
    var openSetlist by mutableStateOf<String?>(null)
        private set

    fun openSet(id: String) {
        selected = null
        openSetlist = id
        atHome = false
    }

    /** Arquivo a destacar depois de "Mostrar na pasta". */
    var pendingFocus by mutableStateOf<String?>(null)

    val scrollMem = HashMap<String, Int>()

    init {
        // Migração: a versão 0.1 tinha uma única pasta "Início".
        val old = prefs.home
        if (old != null) {
            if (old !in pinned) {
                pinned = listOf(old) + pinned
                prefs.pinned = pinned
            }
            prefs.home = null
        }
    }

    private fun startDir(): File {
        val last = prefs.lastDir?.let { File(it) }
        return last?.takeIf { it.isDirectory } ?: Environment.getExternalStorageDirectory()
    }

    fun navigate(to: File) {
        if (!to.isDirectory) return
        selected = null
        dir = to
        atHome = false
        openSetlist = null
    }

    fun goHome() {
        selected = null
        atHome = true
        openSetlist = null
    }

    fun toggleGrid() {
        grid = !grid
        prefs.grid = grid
    }

    fun updateAutoCrop(v: Boolean) {
        autoCrop = v
        prefs.autoCrop = v
    }

    fun setShowPreview(v: Boolean) {
        showPreviewPref = v
        prefs.showPreview = v
    }

    fun saveSplits() {
        prefs.splitPortrait = splitPortrait
        prefs.splitLandscape = splitLandscape
    }

    fun isPinned(path: String) = path in pinned

    fun togglePin(path: String) {
        pinned = if (path in pinned) pinned - path else pinned + path
        prefs.pinned = pinned
    }

    fun movePin(path: String, delta: Int) {
        val i = pinned.indexOf(path)
        val j = i + delta
        if (i < 0 || j !in pinned.indices) return
        val m = pinned.toMutableList()
        m.removeAt(i)
        m.add(j, path)
        pinned = m
        prefs.pinned = pinned
    }

    fun isFav(path: String) = path in favorites

    fun toggleFav(path: String) {
        favorites = if (path in favorites) favorites - path else listOf(path) + favorites
        prefs.favorites = favorites
    }

    /** Onde a busca procura: as pastas do Início; sem nenhuma, o armazenamento interno. */
    fun searchRoots(volumes: List<Volume>): List<File> =
        pinned.map { File(it) }.filter { it.isDirectory }
            .ifEmpty { volumes.map { it.dir }.ifEmpty { listOf(Environment.getExternalStorageDirectory()) } }

    var recents by mutableStateOf(prefs.recentSearches)
        private set

    fun rememberSearch(q: String) {
        val t = q.trim()
        if (t.length < 2) return
        recents = (listOf(t) + recents.filter { !it.equals(t, ignoreCase = true) }).take(20)
        prefs.recentSearches = recents
    }

    fun forgetSearch(q: String) {
        recents = recents - q
        prefs.recentSearches = recents
    }

    fun clearRecents() {
        recents = emptyList()
        prefs.recentSearches = recents
    }

    /** Modo "escolher pasta para o Início": navega normalmente e mostra o botão de adicionar. */
    var pickingFolder by mutableStateOf(false)

    /** Caminho curto para mostrar: relativo à pasta do Início que contém o arquivo. */
    fun shortFolder(f: File): String {
        val parent = f.parent.orEmpty()
        val root = pinned
            .filter { parent == it || parent.startsWith("$it/") }
            .maxByOrNull { it.length }
        if (root == null) {
            return parent.removePrefix(Environment.getExternalStorageDirectory().absolutePath)
                .trimStart('/').replace("/", " › ").ifEmpty { "Memória interna" }
        }
        val rest = parent.removePrefix(root).trimStart('/')
        return File(root).name + if (rest.isEmpty()) "" else " › " + rest.replace("/", " › ")
    }

    fun closeSearch() {
        searching = false
        query = ""
        results = emptyList()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorerScreen(state: ExplorerState, lib: Library, onOpen: (List<File>, Int, String?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val volumes = remember { Fs.volumes(ctx) }
    var menuOpen by remember { mutableStateOf(false) }
    var previewEntry by remember { mutableStateOf<Entry?>(null) }
    var actionsEntry by remember { mutableStateOf<Entry?>(null) }
    var addToSetlist by remember { mutableStateOf<String?>(null) }
    var addToSetlistMany by remember { mutableStateOf<List<String>?>(null) }
    var confirmDelete by remember { mutableStateOf<List<String>?>(null) }
    var prepare by remember { mutableStateOf<List<String>?>(null) }
    var backup by remember { mutableStateOf(false) }
    var newSetlist by remember { mutableStateOf(false) }
    var setItem by remember { mutableStateOf<Pair<Entry, Int>?>(null) }

    // Carrega a pasta atual.
    LaunchedEffect(state.dir, state.reloadTick) {
        state.loading = true
        val d = state.dir
        state.entries = withContext(Dispatchers.IO) { Fs.list(d) }
        state.loading = false
        Prefs(ctx).lastDir = d.absolutePath
    }

    // Itens da tela Início.
    val pinnedEntries by produceState(emptyList<Entry>(), state.pinned, state.reloadTick) {
        value = state.pinned.map { Entry(File(it), Kind.DIR, 0L, 0L) }
    }
    val favEntries by produceState(emptyList<Entry>(), state.favorites, state.reloadTick) {
        value = withContext(Dispatchers.IO) {
            state.favorites.map { p ->
                val f = File(p)
                if (f.exists()) Fs.entry(f) else Entry(f, Fs.kindOf(f, false), 0L, 0L)
            }
        }
    }

    val roots = state.searchRoots(volumes)
    val rootsKey = roots.joinToString("|") { it.absolutePath }

    // Índice em segundo plano: deixa a busca pronta e ajuda a estimar artistas.
    LaunchedEffect(rootsKey) {
        SearchIndex.ensure(ctx, roots, force = false) { }
        state.indexVersion++
        // Atualiza em silêncio (arquivos novos, cartão de memória inserido depois).
        SearchIndex.ensure(ctx, roots, force = true) { }
        state.indexVersion++
    }

    // Monta (ou carrega) o índice da busca ao abrir a busca.
    LaunchedEffect(state.searching, rootsKey) {
        if (state.searching) {
            state.indexing = 0
            SearchIndex.ensure(ctx, roots, force = false) { n -> state.indexing = n }
            state.indexing = null
            state.indexVersion++
        }
    }

    // Busca com pequeno atraso enquanto digita.
    LaunchedEffect(state.query, state.indexVersion, state.searching) {
        if (!state.searching) return@LaunchedEffect
        delay(200)
        val q = state.query
        state.results = if (q.isBlank()) emptyList() else withContext(Dispatchers.Default) { SearchIndex.search(q) }
    }

    val atVolumeRoot = volumes.any { it.dir.absolutePath == state.dir.absolutePath } ||
        state.dir.parentFile == null

    /** Voltar: fecha a busca; de uma pasta do Início (ou da raiz) volta para o Início; senão sobe. */
    fun goBack() {
        when {
            state.searching -> state.closeSearch()
            state.atHome -> Unit
            state.openSetlist != null -> state.goHome()
            state.isPinned(state.dir.absolutePath) || atVolumeRoot -> state.goHome()
            else -> state.dir.parentFile?.let { state.navigate(it) } ?: state.goHome()
        }
    }
    BackHandler(enabled = state.searching || !state.atHome || state.selection.isNotEmpty() || state.clip != null) {
        when {
            state.selection.isNotEmpty() -> state.selection = emptySet()
            state.clip != null && state.atHome -> state.clip = null
            else -> goBack()
        }
    }

    fun open(e: Entry) {
        if (state.searching) state.rememberSearch(state.query)
        if (!e.file.exists()) {
            Toast.makeText(ctx, "Não encontrado: ${e.path}", Toast.LENGTH_LONG).show()
            return
        }
        when (e.kind) {
            Kind.DIR -> state.navigate(e.file)
            Kind.PDF, Kind.IMAGE -> onOpen(listOf(e.file), 0, null)
            Kind.OTHER -> Fs.openExternal(ctx, e.file)
        }
    }

    fun showInFolder(f: File) {
        val parent = f.parentFile ?: return
        state.closeSearch()
        state.pendingFocus = f.absolutePath
        state.navigate(parent)
        state.selected = Fs.entry(f)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val bigScreen = maxWidth >= 600.dp
        val portrait = maxHeight >= maxWidth
        val previewOn = state.showPreviewPref ?: bigScreen
        val showFolderAction = state.searching || state.atHome || state.openSetlist != null

        fun click(e: Entry) {
            if (state.selection.isNotEmpty()) {
                state.toggleSel(e.path)
                return
            }
            when {
                e.kind == Kind.DIR || !e.file.exists() -> open(e)
                previewOn && state.isSel(e.path) -> open(e)
                previewOn -> state.selected = e
                else -> open(e)
            }
        }

        fun longClick(e: Entry) {
            if (e.kind != Kind.DIR && e.file.exists() && !previewOn) {
                previewEntry = e
            } else {
                if (e.kind != Kind.DIR) state.selected = e
                actionsEntry = e
            }
        }

        Scaffold(
            topBar = {
                if (state.selection.isNotEmpty()) {
                    SelectionTopBar(
                        state = state,
                        visible = if (state.searching) state.results else if (state.atHome) emptyList() else state.entries,
                        onAddToSetlist = { addToSetlistMany = state.selection.filter { File(it).isFile } },
                        onCopy = {
                            state.clip = ExplorerState.Clip(state.selection.toList(), move = false)
                            state.selection = emptySet()
                            if (state.searching) state.closeSearch()
                        },
                        onMove = {
                            state.clip = ExplorerState.Clip(state.selection.toList(), move = true)
                            state.selection = emptySet()
                            if (state.searching) state.closeSearch()
                        },
                        onDelete = { confirmDelete = state.selection.toList() },
                        onPrepare = { prepare = state.selection.toList() },
                    )
                } else TopAppBar(
                    title = {
                        when {
                            state.searching -> SearchField(state)
                            state.atHome -> Text("Início")
                            state.openSetlist != null -> {
                                val id = state.openSetlist!!
                                Text(
                                    lib.get(id)?.name
                                        ?: lib.autoSetlists().firstOrNull { it.id == id }?.name
                                        ?: "Setlist",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            else -> Text(
                                Fs.volumeOf(state.dir, volumes)
                                    ?.takeIf { it.dir.absolutePath == state.dir.absolutePath }?.label
                                    ?: state.dir.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        if (state.searching || !state.atHome) {
                            IconButton(onClick = { goBack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar")
                            }
                        }
                    },
                    actions = {
                        if (state.searching) {
                            if (state.query.isNotEmpty()) {
                                IconButton(onClick = { state.query = "" }) {
                                    Icon(Icons.Filled.Clear, "Limpar")
                                }
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    state.indexing = 0
                                    SearchIndex.ensure(ctx, roots, force = true) { n -> state.indexing = n }
                                    state.indexing = null
                                    state.indexVersion++
                                }
                            }) { Icon(Icons.Filled.Refresh, "Reindexar") }
                        } else {
                            IconButton(onClick = { state.searching = true }) {
                                Icon(Icons.Filled.Search, "Buscar")
                            }
                            if (!state.atHome && state.openSetlist == null) {
                                val here = state.dir.absolutePath
                                IconButton(onClick = { state.toggleFav(here) }) {
                                    Icon(
                                        Icons.Filled.Star, "Favoritar esta pasta",
                                        tint = if (state.isFav(here)) FavColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { state.goHome() }) {
                                    Icon(Icons.Filled.Home, "Início")
                                }
                            }
                        }
                        IconButton(onClick = { state.setShowPreview(!previewOn) }) {
                            Icon(
                                painterResource(R.drawable.ic_split),
                                if (previewOn) "Esconder pré-visualização" else "Mostrar pré-visualização",
                                tint = if (previewOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!state.searching && !state.atHome && state.openSetlist == null) {
                            IconButton(onClick = { state.toggleGrid() }) {
                                if (state.grid) {
                                    Icon(Icons.AutoMirrored.Filled.List, "Ver em lista")
                                } else {
                                    Icon(painterResource(R.drawable.ic_grid), "Ver em grade")
                                }
                            }
                        }
                        if (!state.searching) {
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Filled.MoreVert, "Mais")
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    if (!state.atHome && state.openSetlist == null) {
                                        val here = state.dir.absolutePath
                                        val pinnedHere = state.isPinned(here)
                                        DropdownMenuItem(
                                            text = { Text(if (pinnedHere) "Remover esta pasta do Início" else "Adicionar esta pasta ao Início") },
                                            onClick = {
                                                menuOpen = false
                                                state.togglePin(here)
                                                Toast.makeText(
                                                    ctx,
                                                    if (pinnedHere) "Removida do Início" else "Adicionada ao Início",
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text(if (state.autoCrop) "Recorte automático: ligado" else "Recorte automático: desligado") },
                                        leadingIcon = { Icon(painterResource(R.drawable.ic_crop), null) },
                                        onClick = { menuOpen = false; state.updateAutoCrop(!state.autoCrop) },
                                    )
                                    HorizontalDivider()
                                    Row(
                                        Modifier.padding(horizontal = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text("Texto", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 8.dp))
                                        OutlinedButton(onClick = { state.updateTextScale(state.textScale - 0.1f) }) { Text("A−") }
                                        Text(
                                            "${(state.textScale * 100).toInt()}%",
                                            style = MaterialTheme.typography.labelMedium,
                                            modifier = Modifier.padding(horizontal = 8.dp),
                                        )
                                        OutlinedButton(onClick = { state.updateTextScale(state.textScale + 0.1f) }) { Text("A+") }
                                    }
                                    HorizontalDivider()
                                    Text(
                                        "Tamanho das miniaturas",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    )
                                    ThumbNames.forEachIndexed { i, name ->
                                        DropdownMenuItem(
                                            text = { Text((if (state.thumbSize == i) "● " else "○ ") + name) },
                                            onClick = { state.updateThumbSize(i) },
                                        )
                                    }
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Backup…") },
                                        onClick = { menuOpen = false; backup = true },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Atualizar") },
                                        onClick = { menuOpen = false; state.reloadTick++ },
                                    )
                                    HorizontalDivider()
                                    volumes.forEach { v ->
                                        DropdownMenuItem(
                                            text = { Text(v.label) },
                                            leadingIcon = { Icon(painterResource(R.drawable.ic_storage), null) },
                                            onClick = { menuOpen = false; state.navigate(v.dir) },
                                        )
                                    }
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Versão ${appVersion(ctx)}") },
                                        onClick = { menuOpen = false },
                                    )
                                }
                            }
                        }
                    },
                )
            },
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                val browser: @Composable () -> Unit = {
                    Column(Modifier.fillMaxSize()) {
                        when {
                            state.searching -> SearchInfo(state, roots)
                            !state.atHome && state.openSetlist == null -> Breadcrumbs(state.dir, volumes) { state.navigate(it) }
                        }
                        if (state.loading && !state.searching && !state.atHome && state.openSetlist == null) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                        }
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .pinchSteps { d -> state.updateThumbSize(state.thumbSize + d) },
                        ) {
                            when {
                                state.searching -> ResultsList(state, ::click, ::longClick)
                                state.atHome -> HomeContent(
                                    state, lib, pinnedEntries, favEntries, volumes, ::click, ::longClick,
                                    onOpenSetlist = { state.openSet(it) },
                                    onNewSetlist = { newSetlist = true },
                                    onAddFolder = {
                                        state.pickingFolder = true
                                        state.navigate(volumes.first().dir)
                                    },
                                )
                                state.openSetlist != null -> SetlistContent(
                                    state = state,
                                    lib = lib,
                                    id = state.openSetlist!!,
                                    onClickItem = { list, i ->
                                        val e = list[i]
                                        if (previewOn && state.selected?.path != e.path && e.file.exists()) {
                                            state.selected = e
                                        } else if (e.file.exists()) {
                                            val name = lib.get(state.openSetlist!!)?.name ?: "Setlist"
                                            onOpen(list.map { it.file }, i, name)
                                        } else {
                                            open(e)
                                        }
                                    },
                                    onLongItem = { list, i ->
                                        val id = state.openSetlist!!
                                        if (lib.get(id) != null) setItem = list[i] to i else actionsEntry = list[i]
                                    },
                                    onPlay = { files, i ->
                                        val id = state.openSetlist!!
                                        val name = lib.get(id)?.name ?: lib.autoSetlists().firstOrNull { it.id == id }?.name
                                        onOpen(files, i, name)
                                    },
                                    onDeleted = { state.goHome() },
                                )
                                else -> FolderContent(state, ::click, ::longClick)
                            }
                        }
                        val c = state.clip
                        if (c != null && !state.searching) {
                            PasteBar(
                                clip = c,
                                canPaste = !state.atHome && state.openSetlist == null,
                                here = state.dir.name,
                                onPaste = {
                                    val dest = state.dir
                                    state.clip = null
                                    scope.launch {
                                        val r = if (c.move) FileOps.move(c.paths, dest) else FileOps.copy(c.paths, dest)
                                        lib.relink(r.moved)
                                        AnnStore.relink(ctx, r.moved)
                                        state.relink(r.moved)
                                        state.reloadTick++
                                        Toast.makeText(
                                            ctx,
                                            "${if (c.move) "Movidos" else "Copiados"}: ${r.done}" +
                                                if (r.failed.isNotEmpty()) " • falharam: ${r.failed.joinToString()}" else "",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                },
                                onCancel = { state.clip = null },
                            )
                        }
                        if (state.pickingFolder && !state.atHome && state.openSetlist == null && !state.searching) {
                            PickFolderBar(
                                state = state,
                                volumes = volumes,
                                onAdd = {
                                    val here = state.dir.absolutePath
                                    if (!state.isPinned(here)) state.togglePin(here)
                                    state.pickingFolder = false
                                    Toast.makeText(ctx, "\"${state.dir.name}\" adicionada ao Início", Toast.LENGTH_SHORT).show()
                                    state.goHome()
                                },
                                onCancel = { state.pickingFolder = false; state.goHome() },
                            )
                        }
                    }
                }
                if (!previewOn) {
                    browser()
                } else {
                    val preview: @Composable () -> Unit = {
                        val sel = state.selected
                        PreviewPane(
                            entry = sel,
                            crop = state.autoCrop,
                            favorite = sel != null && state.isFav(sel.path),
                            onToggleFav = { e -> state.toggleFav(e.path) },
                            onAddToSetlist = { e -> addToSetlist = e.path },
                            onOpen = { open(it) },
                            onShowFolder = if (showFolderAction) ({ f: File -> showInFolder(f) }) else null,
                        )
                    }
                    SplitLayout(
                        portrait = portrait,
                        fraction = if (portrait) state.splitPortrait else state.splitLandscape,
                        onFraction = { if (portrait) state.splitPortrait = it else state.splitLandscape = it },
                        onDone = { state.saveSplits() },
                        preview = preview,
                        browser = browser,
                    )
                }
            }
        }

        previewEntry?.let { e ->
            Dialog(
                onDismissRequest = { previewEntry = null },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(
                    Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.92f),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    PreviewPane(
                        entry = e,
                        crop = state.autoCrop,
                        favorite = state.isFav(e.path),
                        onToggleFav = { x -> state.toggleFav(x.path) },
                        onAddToSetlist = { x -> previewEntry = null; addToSetlist = x.path },
                        onOpen = { previewEntry = null; open(it) },
                        onShowFolder = if (showFolderAction) ({ f: File -> previewEntry = null; showInFolder(f) }) else null,
                    )
                }
            }
        }

        addToSetlist?.let { p ->
            AddToSetlistDialog(lib, listOf(p)) { addToSetlist = null }
        }
        addToSetlistMany?.let { ps ->
            AddToSetlistDialog(lib, ps) { addToSetlistMany = null; state.selection = emptySet() }
        }
        if (backup) BackupDialog(state, volumes) { backup = false }
        prepare?.let { ps ->
            PrepareFilesDialog(
                ps, volumes, lib, state.autoCrop,
                onRelink = { state.relink(it); state.reloadTick++ },
            ) { prepare = null; state.selection = emptySet() }
        }
        confirmDelete?.let { ps ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmDelete = null },
                title = { Text("Apagar ${ps.size} ${if (ps.size == 1) "item" else "itens"}?") },
                text = {
                    Text(
                        "Vão para a pasta Estante-Lixeira (na raiz da memória ou do cartão). " +
                            "Dá para recuperar de lá; para apagar de vez, apague essa pasta."
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        confirmDelete = null
                        state.selection = emptySet()
                        scope.launch {
                            val r = FileOps.trash(ps, volumes)
                            state.reloadTick++
                            Toast.makeText(
                                ctx,
                                "Na lixeira: ${r.done}" + if (r.failed.isNotEmpty()) " • falharam: ${r.failed.joinToString()}" else "",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }) { Text("Apagar") }
                },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = null }) { Text("Cancelar") } },
            )
        }
        if (newSetlist) {
            NameDialog("Novo setlist", "", onDismiss = { newSetlist = false }) { name ->
                newSetlist = false
                val s = lib.create(name)
                state.openSet(s.id)
            }
        }
        setItem?.let { (e, i) ->
            SetlistItemDialog(
                lib = lib,
                setlistId = state.openSetlist ?: "",
                index = i,
                entry = e,
                onShowFolder = { setItem = null; showInFolder(e.file) },
                onAddOther = { setItem = null; addToSetlist = e.path },
                onDismiss = { setItem = null },
            )
        }

        actionsEntry?.let { e ->
            ActionsDialog(
                e = e,
                state = state,
                onSelect = if (!state.atHome || state.searching) ({ actionsEntry = null; state.selection = setOf(e.path) }) else null,
                onAddToSetlist = if (e.kind != Kind.DIR) ({ actionsEntry = null; addToSetlist = e.path }) else null,
                onPrepare = if (e.kind == Kind.PDF || e.kind == Kind.IMAGE) ({ actionsEntry = null; prepare = listOf(e.path) }) else null,
                onDismiss = { actionsEntry = null },
                onShowFolder = if (showFolderAction && e.kind != Kind.DIR) ({ actionsEntry = null; showInFolder(e.file) }) else null,
            )
        }
    }
}

/**
 * Pré-visualização + lista com divisória arrastável.
 * Em pé: pré-visualização em cima, lista embaixo. Deitado: lista à esquerda, pré-visualização à direita.
 */
@Composable
private fun SplitLayout(
    portrait: Boolean,
    fraction: Float,
    onFraction: (Float) -> Unit,
    onDone: () -> Unit,
    preview: @Composable () -> Unit,
    browser: @Composable () -> Unit,
) {
    val frac by rememberUpdatedState(fraction.coerceIn(0.15f, 0.92f))
    val setFrac by rememberUpdatedState(onFraction)
    val done by rememberUpdatedState(onDone)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val totalPx = if (portrait) constraints.maxHeight.toFloat() else constraints.maxWidth.toFloat()
        val total by rememberUpdatedState(totalPx)
        val handle: @Composable () -> Unit = {
            Box(
                Modifier
                    .then(if (portrait) Modifier.fillMaxWidth().height(22.dp) else Modifier.fillMaxHeight().width(22.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .pointerInput(portrait) {
                        detectDragGestures(onDragEnd = { done() }, onDragCancel = { done() }) { change, amount ->
                            change.consume()
                            val d = if (portrait) amount.y / total else -amount.x / total
                            setFrac((frac + d).coerceIn(0.15f, 0.92f))
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .then(if (portrait) Modifier.size(width = 56.dp, height = 5.dp) else Modifier.size(width = 5.dp, height = 56.dp))
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.outline)
                )
            }
        }
        if (portrait) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(frac).fillMaxWidth()) { preview() }
                handle()
                Box(Modifier.weight(1f - frac).fillMaxWidth()) { browser() }
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f - frac).fillMaxHeight()) { browser() }
                handle()
                Box(Modifier.weight(frac).fillMaxHeight()) { preview() }
            }
        }
    }
}

private fun appVersion(ctx: android.content.Context): String = try {
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
} catch (e: Exception) {
    "?"
}

/** Menu ao segurar o dedo: Início, favoritos, ordem. */
@Composable
private fun ActionsDialog(
    e: Entry,
    state: ExplorerState,
    onSelect: (() -> Unit)?,
    onAddToSetlist: (() -> Unit)?,
    onPrepare: (() -> Unit)?,
    onDismiss: () -> Unit,
    onShowFolder: (() -> Unit)?,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(vertical = 8.dp).fillMaxWidth()) {
                Text(
                    e.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
                Text(
                    state.shortFolder(e.file),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                )
                HorizontalDivider()
                if (onSelect != null) ActionRow("Selecionar vários (mover, copiar, apagar…)") { onSelect() }
                val pinned = state.isPinned(e.path)
                if (e.kind == Kind.DIR) {
                    ActionRow(if (pinned) "Remover do Início" else "Adicionar ao Início") {
                        state.togglePin(e.path); onDismiss()
                    }
                }
                ActionRow(if (state.isFav(e.path)) "Remover dos favoritos" else "Adicionar aos favoritos") {
                    state.toggleFav(e.path); onDismiss()
                }
                if (pinned && state.atHome && !state.searching) {
                    val i = state.pinned.indexOf(e.path)
                    if (i > 0) ActionRow("Mover para antes") { state.movePin(e.path, -1); onDismiss() }
                    if (i < state.pinned.lastIndex) ActionRow("Mover para depois") { state.movePin(e.path, 1); onDismiss() }
                }
                if (onAddToSetlist != null) ActionRow("Adicionar a setlist…") { onAddToSetlist() }
                if (onPrepare != null) ActionRow("Gravar ajustes de imagem no arquivo (modo legível)…") { onPrepare() }
                if (onShowFolder != null) ActionRow("Mostrar na pasta") { onShowFolder() }
            }
        }
    }
}

@Composable
internal fun ActionRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    )
}

@Composable
private fun SearchField(state: ExplorerState) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    TextField(
        value = state.query,
        onValueChange = { state.query = it },
        singleLine = true,
        placeholder = { Text("Título, artista, pasta…") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { state.rememberSearch(state.query); keyboard?.hide() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
}

@Composable
private fun SearchInfo(state: ExplorerState, roots: List<File>) {
    val n = state.indexing
    val where = when {
        state.pinned.isEmpty() -> "memória interna"
        roots.size == 1 -> roots[0].name
        else -> "${roots.size} pastas do Início"
    }
    val text = when {
        n != null -> "Indexando $where… $n arquivos"
        state.query.isBlank() -> "Buscando em $where (${SearchIndex.size} arquivos). Várias palavras: todas precisam aparecer."
        else -> "${state.results.size}${if (state.results.size >= 400) "+" else ""} resultados em $where"
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (n != null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp).height(2.dp))
    }
}

@Composable
private fun Breadcrumbs(dir: File, volumes: List<Volume>, onClick: (File) -> Unit) {
    val crumbs: List<Pair<String, File>> = remember(dir) {
        val vol = Fs.volumeOf(dir, volumes)
        if (vol == null) {
            listOf(dir.absolutePath to dir)
        } else {
            val list = mutableListOf(vol.label to vol.dir)
            var cur = vol.dir
            dir.absolutePath.removePrefix(vol.dir.absolutePath).trim('/')
                .split('/').filter { it.isNotEmpty() }
                .forEach { seg ->
                    cur = File(cur, seg)
                    list.add(seg to cur)
                }
            list
        }
    }
    val scroll = rememberScrollState()
    LaunchedEffect(dir) {
        snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) }
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { i, (label, f) ->
            if (i > 0) {
                Text("›", Modifier.padding(horizontal = 2.dp), color = MaterialTheme.colorScheme.outline)
            }
            val last = i == crumbs.lastIndex
            Text(
                label,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onClick(f) }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = if (last) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** Tela Início: pastas escolhidas, favoritos e os armazenamentos do aparelho. */
@Composable
private fun HomeContent(
    state: ExplorerState,
    lib: Library,
    pinned: List<Entry>,
    favs: List<Entry>,
    volumes: List<Volume>,
    onClick: (Entry) -> Unit,
    onLong: (Entry) -> Unit,
    onOpenSetlist: (String) -> Unit,
    onNewSetlist: () -> Unit,
    onAddFolder: () -> Unit,
) {
    val gs = remember { LazyGridState(state.scrollMem["#home"] ?: 0) }
    val autos = remember(lib.history) { lib.autoSetlists() }
    DisposableEffect(gs) { onDispose { state.scrollMem["#home"] = gs.firstVisibleItemIndex } }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(GridMin[state.thumbSize].dp),
        state = gs,
        contentPadding = PaddingValues(6.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "h-sets") { SectionHeader("Setlists") }
        items(lib.setlists.size, key = { "s:" + lib.setlists[it].id }) { i ->
            val sl = lib.setlists[i]
            GridItem(
                e = Entry(File(sl.id), Kind.OTHER, 0L, 0L),
                label = sl.name,
                selected = false,
                fav = false,
                subtitle = "${sl.items.size} ${if (sl.items.size == 1) "música" else "músicas"}",
                dim = false,
                onClick = { onOpenSetlist(sl.id) },
                onLong = { onOpenSetlist(sl.id) },
                icon = R.drawable.ic_setlist,
            )
        }
        item(key = "s:new") {
            GridItem(
                e = Entry(File("novo"), Kind.OTHER, 0L, 0L),
                label = "Novo setlist",
                selected = false,
                fav = false,
                subtitle = null,
                dim = false,
                onClick = onNewSetlist,
                onLong = onNewSetlist,
                icon = R.drawable.ic_add,
            )
        }
        if (autos.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "h-auto") { SectionHeader("Setlists automáticos (pelo que você toca)") }
            items(autos.size, key = { "a:" + autos[it].id }) { i ->
                val a = autos[i]
                GridItem(
                    e = Entry(File(a.id), Kind.OTHER, 0L, 0L),
                    label = a.name,
                    selected = false,
                    fav = false,
                    subtitle = if (a.id == "auto:top") "${a.items.size} músicas" else "artista • ${a.items.size}",
                    dim = false,
                    onClick = { onOpenSetlist(a.id) },
                    onLong = { onOpenSetlist(a.id) },
                    icon = R.drawable.ic_setlist,
                )
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }, key = "h-pastas") { SectionHeader("Pastas") }
        if (pinned.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "e-pastas") {
                HintText(
                    "Nenhuma pasta escolhida ainda. Entre numa pasta (por Armazenamento, abaixo) e, " +
                        "no menu ⋮, toque em \"Adicionar esta pasta ao Início\". " +
                        "Ou segure o dedo sobre uma pasta."
                )
            }
        }
        items(pinned.size, key = { "p:" + pinned[it].path }) { i ->
            val e = pinned[i]
            GridItem(
                e = e,
                selected = false,
                fav = state.isFav(e.path),
                subtitle = e.file.parentFile?.name,
                dim = !e.file.isDirectory,
                onClick = { onClick(e) },
                onLong = { onLong(e) },
                thumbPx = GridPx[state.thumbSize],
            )
        }

        item(key = "p:add") {
            GridItem(
                e = Entry(File("adicionar"), Kind.OTHER, 0L, 0L),
                label = "Adicionar pasta",
                selected = false,
                fav = false,
                subtitle = "memória ou cartão",
                dim = false,
                onClick = onAddFolder,
                onLong = onAddFolder,
                icon = R.drawable.ic_add,
            )
        }

        item(span = { GridItemSpan(maxLineSpan) }, key = "h-favs") { SectionHeader("Favoritos") }
        if (favs.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "e-favs") {
                HintText(
                    "Nenhum favorito ainda. Toque na estrela ★ na pré-visualização de uma partitura, " +
                        "ou segure o dedo sobre um arquivo ou pasta."
                )
            }
        }
        items(favs.size, key = { "f:" + favs[it].path }) { i ->
            val e = favs[i]
            GridItem(
                e = e,
                selected = state.isSel(e.path),
                fav = false,
                subtitle = e.file.parentFile?.name,
                dim = !e.file.exists(),
                onClick = { onClick(e) },
                onLong = { onLong(e) },
                thumbPx = GridPx[state.thumbSize],
            )
        }

        item(span = { GridItemSpan(maxLineSpan) }, key = "h-vol") { SectionHeader("Armazenamento") }
        items(volumes.size, key = { "v:" + volumes[it].dir.absolutePath }) { i ->
            val v = volumes[i]
            val e = Entry(v.dir, Kind.DIR, 0L, 0L)
            GridItem(
                e = e,
                label = v.label,
                selected = false,
                fav = false,
                subtitle = null,
                dim = false,
                onClick = { onClick(e) },
                onLong = { onLong(e) },
                icon = R.drawable.ic_storage,
            )
        }
    }
}

@Composable
internal fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
internal fun HintText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

@Composable
private fun FolderContent(state: ExplorerState, onClick: (Entry) -> Unit, onLong: (Entry) -> Unit) {
    val entries = state.entries
    if (entries.isEmpty() && !state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Pasta vazia", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val key = state.dir.absolutePath
    if (state.grid) {
        val gs = remember(key) { LazyGridState(state.scrollMem[key] ?: 0) }
        DisposableEffect(gs) { onDispose { state.scrollMem[key] = gs.firstVisibleItemIndex } }
        LaunchedEffect(entries, state.pendingFocus) {
            val target = state.pendingFocus ?: return@LaunchedEffect
            val i = entries.indexOfFirst { it.path == target }
            if (i >= 0) {
                gs.scrollToItem(i)
                state.pendingFocus = null
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(GridMin[state.thumbSize].dp),
            state = gs,
            contentPadding = PaddingValues(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(entries.size, key = { entries[it].path }) { i ->
                val e = entries[i]
                GridItem(
                    e = e,
                    selected = state.isSel(e.path),
                    fav = state.isFav(e.path),
                    subtitle = null,
                    dim = false,
                    onClick = { onClick(e) },
                    onLong = { onLong(e) },
                    pinnedMark = e.kind == Kind.DIR && state.isPinned(e.path),
                    thumbPx = GridPx[state.thumbSize],
                )
            }
        }
    } else {
        val ls = remember(key) { LazyListState(state.scrollMem[key] ?: 0) }
        DisposableEffect(ls) { onDispose { state.scrollMem[key] = ls.firstVisibleItemIndex } }
        LaunchedEffect(entries, state.pendingFocus) {
            val target = state.pendingFocus ?: return@LaunchedEffect
            val i = entries.indexOfFirst { it.path == target }
            if (i >= 0) {
                ls.scrollToItem(i)
                state.pendingFocus = null
            }
        }
        LazyColumn(state = ls, modifier = Modifier.fillMaxSize()) {
            items(entries, key = { it.path }) { e ->
                ListItemRow(e, null, state.isSel(e.path), state.isFav(e.path), { onClick(e) }, { onLong(e) }, state.thumbSize)
            }
        }
    }
}

@Composable
private fun ResultsList(state: ExplorerState, onClick: (Entry) -> Unit, onLong: (Entry) -> Unit) {
    if (state.query.isBlank()) {
        RecentSearches(state)
        return
    }
    val tokens = remember(state.query) { Fs.norm(state.query).split(' ', '\t').filter { it.isNotBlank() } }
    LazyColumn(Modifier.fillMaxSize()) {
        items(state.results, key = { it.path }) { e ->
            ListItemRow(
                e, state.shortFolder(e.file), state.isSel(e.path), state.isFav(e.path),
                { onClick(e) }, { onLong(e) }, state.thumbSize,
                highlight = tokens,
            )
        }
    }
}

/** Lista de buscas recentes (aparece com o campo de busca vazio). */
@Composable
private fun RecentSearches(state: ExplorerState) {
    if (state.recents.isEmpty()) {
        HintText("As buscas que você fizer aparecem aqui para repetir com um toque.")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Buscas recentes",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.TextButton(onClick = { state.clearRecents() }) { Text("Limpar") }
            }
        }
        items(state.recents, key = { it }) { q ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { state.query = q }
                    .padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.outline)
                Text(q, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp).weight(1f))
                IconButton(onClick = { state.forgetSearch(q) }) {
                    Icon(Icons.Filled.Clear, "Remover das recentes", tint = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

/** Destaca no texto as palavras buscadas, ignorando acentos e maiúsculas. */
internal fun highlighted(text: String, tokens: List<String>, bg: Color, fg: Color): androidx.compose.ui.text.AnnotatedString {
    if (tokens.isEmpty()) return androidx.compose.ui.text.AnnotatedString(text)
    // Texto normalizado + mapa de cada posição normalizada para a posição original.
    val sb = StringBuilder()
    val map = ArrayList<Int>()
    text.forEachIndexed { i, c ->
        val n = Fs.norm(c.toString())
        for (ch in n) {
            sb.append(ch)
            map.add(i)
        }
    }
    val norm = sb.toString()
    val marks = BooleanArray(text.length)
    for (t in tokens) {
        var from = 0
        while (true) {
            val k = norm.indexOf(t, from)
            if (k < 0) break
            for (j in k until k + t.length) marks[map[j]] = true
            from = k + 1
        }
    }
    val style = androidx.compose.ui.text.SpanStyle(
        background = bg, color = fg, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
    )
    return androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        var i = 0
        while (i < text.length) {
            if (marks[i]) {
                var j = i
                while (j < text.length && marks[j]) j++
                addStyle(style, i, j)
                i = j
            } else {
                i++
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GridItem(
    e: Entry,
    selected: Boolean,
    fav: Boolean,
    subtitle: String?,
    dim: Boolean,
    onClick: () -> Unit,
    onLong: () -> Unit,
    label: String = e.title,
    icon: Int? = null,
    pinnedMark: Boolean = false,
    thumbPx: Int = 320,
) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Column(
        Modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(6.dp)
            .alpha(if (dim) 0.4f else 1f),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Icon(painterResource(icon), null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            } else {
                KindVisual(e, thumbPx, Modifier.fillMaxSize(), iconSize = 56)
            }
            if (fav) {
                Icon(
                    Icons.Filled.Star, "Favorito",
                    Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp),
                    tint = FavColor,
                )
            }
            if (pinnedMark) {
                Icon(
                    Icons.Filled.Home, "No Início",
                    Modifier.align(Alignment.TopStart).padding(4.dp).size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (dim) "$label (não encontrado)" else label,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ListItemRow(
    e: Entry,
    subtitle: String?,
    selected: Boolean,
    fav: Boolean,
    onClick: () -> Unit,
    onLong: () -> Unit,
    size: Int = 1,
    title: String = e.title,
    highlight: List<String> = emptyList(),
) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = ListW[size].dp, height = (ListW[size] * 1.32f).dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            KindVisual(e, ListPx[size], Modifier.fillMaxSize(), iconSize = 26)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val hl = MaterialTheme.colorScheme.tertiaryContainer
            val hlText = MaterialTheme.colorScheme.onTertiaryContainer
            Text(
                highlighted(title, highlight, hl, hlText),
                style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val meta = subtitle ?: if (e.kind == Kind.DIR) "Pasta" else "${Fs.humanSize(e.size)} • ${Fs.humanDate(e.modified)}"
            Text(
                highlighted(meta, highlight, hl, hlText),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (fav) {
            Icon(Icons.Filled.Star, "Favorito", Modifier.padding(start = 8.dp).size(20.dp), tint = FavColor)
        }
    }
}

/** Miniatura para PDF/imagem, ícone para pasta e outros arquivos. */
@Composable
internal fun KindVisual(e: Entry, thumbW: Int, modifier: Modifier, iconSize: Int) {
    when (e.kind) {
        Kind.DIR -> Icon(
            painterResource(R.drawable.ic_folder), null,
            Modifier.size(iconSize.dp), tint = MaterialTheme.colorScheme.primary,
        )
        Kind.OTHER -> Icon(
            painterResource(R.drawable.ic_file), null,
            Modifier.size(iconSize.dp), tint = MaterialTheme.colorScheme.outline,
        )
        Kind.PDF, Kind.IMAGE -> {
            val ctx = LocalContext.current
            val bmp by produceState(Thumbs.cached(e, thumbW), e.path, e.modified) {
                if (value == null && e.file.exists()) value = Thumbs.load(ctx, e, thumbW)
            }
            val b = bmp
            if (b != null) {
                Image(b.asImageBitmap(), null, modifier, contentScale = ContentScale.Fit)
            } else {
                Text(
                    if (e.kind == Kind.PDF) "PDF" else "IMG",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/**
 * Pré-visualização: a página ocupa o máximo de espaço.
 * Toque na esquerda/direita da página ou deslize para trocar de página; toque no meio abre o leitor.
 */
@Composable
fun PreviewPane(
    entry: Entry?,
    crop: Boolean,
    favorite: Boolean,
    onToggleFav: (Entry) -> Unit,
    onAddToSetlist: (Entry) -> Unit,
    onOpen: (Entry) -> Unit,
    onShowFolder: ((File) -> Unit)?,
) {
    if (entry == null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "Toque num arquivo para pré-visualizar.\nToque de novo para abrir.\nSegure o dedo para mais opções.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val viewable = entry.kind == Kind.PDF || entry.kind == Kind.IMAGE
    val doc = remember(entry.path) { if (viewable) openDoc(entry.file) else null }
    DisposableEffect(doc) { onDispose { doc?.close() } }
    var page by remember(entry.path) { mutableIntStateOf(doc?.startPage ?: 0) }
    var zoom by remember(entry.path, page) { mutableFloatStateOf(1f) }
    var pan0 by remember(entry.path, page) { mutableStateOf(Offset.Zero) }
    val pages = doc?.pageCount ?: 0
    val pageNow by rememberUpdatedState(page)
    val pagesNow by rememberUpdatedState(pages)
    val openNow by rememberUpdatedState {
        val f = doc?.pageFiles?.getOrNull(page)
        onOpen(if (f != null && f != entry.file) Fs.entry(f) else entry)
    }
    fun go(d: Int) {
        val t = (pageNow + d).coerceIn(0, (pagesNow - 1).coerceAtLeast(0))
        page = t
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp)) {
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .pointerInput(entry.path) {
                    // Pinça para ampliar a página; com zoom, um dedo arrasta.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        do {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val fingers = ev.changes.count { it.pressed }
                            if (fingers >= 2 || zoom > 1.01f) {
                                val z = ev.calculateZoom()
                                val pan = ev.calculatePan()
                                val c = ev.calculateCentroid(useCurrent = true)
                                var ns = (zoom * z).coerceIn(1f, 5f)
                                val center = Offset(size.width / 2f, size.height / 2f)
                                var t = pan0
                                if (c != Offset.Unspecified) {
                                    val g = c - center
                                    t = g - (g - t) * (ns / zoom)
                                }
                                t += pan
                                if (ns < 1.02f) {
                                    ns = 1f
                                    t = Offset.Zero
                                }
                                val mx = (ns - 1f) * size.width / 2f
                                val my = (ns - 1f) * size.height / 2f
                                pan0 = Offset(t.x.coerceIn(-mx, mx), t.y.coerceIn(-my, my))
                                zoom = ns
                                ev.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (ev.changes.any { it.pressed })
                    }
                }
                .pointerInput(entry.path) {
                    detectTapGestures { p ->
                        val x = p.x / size.width
                        when {
                            zoom > 1.01f -> { zoom = 1f; pan0 = Offset.Zero }
                            x < 0.25f && pagesNow > 1 -> go(-1)
                            x > 0.75f && pagesNow > 1 -> go(1)
                            else -> openNow()
                        }
                    }
                }
                .pointerInput(entry.path) {
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = {
                            if (zoom <= 1.01f && abs(total) > 60f) go(if (total < 0) 1 else -1)
                        },
                    ) { change, amount ->
                        change.consume()
                        total += amount
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val w = constraints.maxWidth
            val h = constraints.maxHeight
            when {
                doc != null -> {
                    val ctxP = LocalContext.current
                    val bmp by produceState<Bitmap?>(null, doc, page, w, h, crop) {
                        // Ajustes de imagem e recorte manual salvos para esta partitura (ou desta imagem da pasta).
                        val src = doc.pageFiles?.getOrNull(page)?.absolutePath ?: entry.path
                        val pg = if (doc.pageFiles != null) 0 else page
                        val nt = withContext(Dispatchers.IO) { AnnStore.load(ctxP, src) }
                        value = doc.render(page, (w * 1.5f).toInt(), (h * 1.5f).toInt(), crop, nt.look ?: Look(), nt.cropFor(pg))
                    }
                    val b = bmp
                    if (b != null) {
                        Image(
                            b.asImageBitmap(), null,
                            Modifier.fillMaxSize().graphicsLayer {
                                scaleX = zoom
                                scaleY = zoom
                                translationX = pan0.x
                                translationY = pan0.y
                            },
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        CircularProgressIndicator()
                    }
                }
                viewable -> Text("Não foi possível abrir este arquivo")
                else -> Icon(
                    painterResource(R.drawable.ic_file), null,
                    Modifier.size(72.dp), tint = MaterialTheme.colorScheme.outline,
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val oneRow = maxWidth >= 560.dp
            val pager: @Composable () -> Unit = {
                if (pages > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalIconButton(
                            enabled = page > 0,
                            onClick = { go(-1) },
                            modifier = Modifier.size(56.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Página anterior", Modifier.size(36.dp))
                        }
                        Text(
                            "${page + 1} / $pages",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                        FilledTonalIconButton(
                            enabled = page < pages - 1,
                            onClick = { go(1) },
                            modifier = Modifier.size(56.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Próxima página", Modifier.size(36.dp))
                        }
                    }
                }
            }
            val info: @Composable (Modifier) -> Unit = { m ->
                Column(m) {
                    val shownTitle = doc?.pageFiles?.getOrNull(page)?.nameWithoutExtension ?: entry.title
                    Text(shownTitle, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val kindLabel = when (entry.kind) {
                        Kind.PDF -> "$pages ${if (pages == 1) "pág." else "págs."}"
                        Kind.IMAGE -> if (pages > 1) "imagem ${page + 1} de $pages da pasta" else "Imagem"
                        else -> entry.file.extension.uppercase().ifEmpty { "Arquivo" }
                    }
                    Text(
                        "${entry.file.parentFile?.name.orEmpty()} • $kindLabel • ${Fs.humanSize(entry.size)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val actions: @Composable () -> Unit = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (favorite) {
                        FilledTonalIconButton(onClick = { onToggleFav(entry) }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.Star, "Remover dos favoritos", tint = FavColor)
                        }
                    } else {
                        OutlinedIconButton(onClick = { onToggleFav(entry) }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.Star, "Adicionar aos favoritos", tint = MaterialTheme.colorScheme.outline)
                        }
                    }
                    OutlinedIconButton(onClick = { onAddToSetlist(entry) }, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_playlist_add), "Adicionar a setlist")
                    }
                    if (onShowFolder != null) {
                        OutlinedButton(onClick = { onShowFolder(entry.file) }) { Text("Pasta") }
                    }
                    Button(onClick = { openNow() }) {
                        Text(if (viewable) "Abrir" else "Abrir com…")
                    }
                }
            }
            if (oneRow) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    pager()
                    Spacer(Modifier.width(12.dp))
                    info(Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    actions()
                }
            } else {
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { pager() }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        info(Modifier.weight(1f))
                        actions()
                    }
                }
            }
        }
    }
}

/** Barra do modo "escolher pasta": entre na pasta desejada e toque em Adicionar. */
@Composable
private fun PickFolderBar(state: ExplorerState, volumes: List<Volume>, onAdd: () -> Unit, onCancel: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                "Entre na pasta que quer no Início e toque em Adicionar.",
                style = MaterialTheme.typography.labelMedium,
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                volumes.forEach { v ->
                    androidx.compose.material3.TextButton(onClick = { state.navigate(v.dir) }) { Text(v.label) }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.TextButton(onClick = onCancel) { Text("Cancelar") }
                Button(onClick = onAdd) {
                    Text("Adicionar \"${state.dir.name}\"", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Pinça na lista/grade: abrir os dedos aumenta as miniaturas, fechar diminui. Um dedo continua rolando. */
private fun Modifier.pinchSteps(onStep: (Int) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var acc = 1f
        do {
            val ev = awaitPointerEvent(PointerEventPass.Initial)
            if (ev.changes.count { it.pressed } >= 2) {
                acc *= ev.calculateZoom()
                if (acc > 1.35f) {
                    onStep(1)
                    acc = 1f
                } else if (acc < 0.74f) {
                    onStep(-1)
                    acc = 1f
                }
                ev.changes.forEach { it.consume() }
            }
        } while (ev.changes.any { it.pressed })
    }
}

/** Barra de título durante a seleção múltipla. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    state: ExplorerState,
    visible: List<Entry>,
    onAddToSetlist: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onPrepare: () -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = { state.selection = emptySet() }) { Icon(Icons.Filled.Clear, "Sair da seleção") }
        },
        title = { Text("${state.selection.size} selecionado(s)") },
        actions = {
            if (visible.isNotEmpty()) {
                androidx.compose.material3.TextButton(onClick = {
                    state.selection = if (state.selection.size == visible.size) emptySet() else visible.map { it.path }.toSet()
                }) { Text("Todos") }
            }
            IconButton(onClick = onAddToSetlist) {
                Icon(painterResource(R.drawable.ic_playlist_add), "Adicionar a setlist")
            }
            androidx.compose.material3.TextButton(onClick = onCopy) { Text("Copiar") }
            androidx.compose.material3.TextButton(onClick = onMove) { Text("Mover") }
            androidx.compose.material3.TextButton(onClick = onPrepare) { Text("Legível") }
            androidx.compose.material3.TextButton(onClick = onDelete) {
                Text("Apagar", color = MaterialTheme.colorScheme.error)
            }
        },
    )
}

/** Barra "colar aqui": aparece depois de Copiar/Mover, enquanto você escolhe a pasta de destino. */
@Composable
private fun PasteBar(clip: ExplorerState.Clip, canPaste: Boolean, here: String, onPaste: () -> Unit, onCancel: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                "${if (clip.move) "Mover" else "Copiar"} ${clip.paths.size} ${if (clip.paths.size == 1) "item" else "itens"}: " +
                    "abra a pasta de destino e toque em Colar.",
                style = MaterialTheme.typography.labelMedium,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.TextButton(onClick = onCancel) { Text("Cancelar") }
                Button(enabled = canPaste, onClick = onPaste) {
                    Text("Colar em \"$here\"", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
