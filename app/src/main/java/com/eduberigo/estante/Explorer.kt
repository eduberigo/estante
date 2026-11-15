package com.eduberigo.estante

import android.graphics.Bitmap
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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

/** Estado do explorador. Fica vivo enquanto o leitor está aberto, para voltar ao mesmo ponto. */
class ExplorerState(private val prefs: Prefs) {
    var dir by mutableStateOf(startDir())
        private set
    var entries by mutableStateOf<List<Entry>>(emptyList())
    var loading by mutableStateOf(false)
    var selected by mutableStateOf<Entry?>(null)
    var grid by mutableStateOf(prefs.grid)
        private set
    var home by mutableStateOf(prefs.home)
        private set

    var searching by mutableStateOf(false)
    var query by mutableStateOf("")
    var results by mutableStateOf<List<Entry>>(emptyList())
    var indexing by mutableStateOf<Int?>(null)
    var indexVersion by mutableIntStateOf(0)
    var reloadTick by mutableIntStateOf(0)

    /** Arquivo a destacar depois de "Mostrar na pasta". */
    var pendingFocus by mutableStateOf<String?>(null)

    val scrollMem = HashMap<String, Int>()

    private fun startDir(): File {
        val candidates = listOfNotNull(prefs.lastDir, prefs.home).map { File(it) }
        return candidates.firstOrNull { it.isDirectory } ?: Environment.getExternalStorageDirectory()
    }

    fun navigate(to: File) {
        if (!to.isDirectory) return
        selected = null
        dir = to
    }

    fun toggleGrid() {
        grid = !grid
        prefs.grid = grid
    }

    fun setHomeHere() {
        home = dir.absolutePath
        prefs.home = home
    }

    fun closeSearch() {
        searching = false
        query = ""
        results = emptyList()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorerScreen(state: ExplorerState, onOpen: (File) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val volumes = remember { Fs.volumes(ctx) }
    var menuOpen by remember { mutableStateOf(false) }
    var dialogEntry by remember { mutableStateOf<Entry?>(null) }

    val homeDir: File = state.home?.let { File(it) }?.takeIf { it.isDirectory }
        ?: volumes.first().dir

    // Carrega a pasta atual.
    LaunchedEffect(state.dir, state.reloadTick) {
        state.loading = true
        val d = state.dir
        state.entries = withContext(Dispatchers.IO) { Fs.list(d) }
        state.loading = false
        ctxPrefsSave(ctx, d)
    }

    // Monta (ou carrega) o índice da busca ao abrir a busca.
    LaunchedEffect(state.searching, homeDir.absolutePath) {
        if (state.searching) {
            state.indexing = 0
            SearchIndex.ensure(ctx, homeDir, force = false) { n -> state.indexing = n }
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
    androidx.activity.compose.BackHandler(enabled = state.searching || !atVolumeRoot) {
        if (state.searching) state.closeSearch() else state.dir.parentFile?.let { state.navigate(it) }
    }

    fun open(e: Entry) {
        when (e.kind) {
            Kind.DIR -> state.navigate(e.file)
            Kind.PDF, Kind.IMAGE -> onOpen(e.file)
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
        val wide = maxWidth >= 700.dp

        fun click(e: Entry) {
            when {
                e.kind == Kind.DIR -> state.navigate(e.file)
                wide && state.selected?.path == e.path -> open(e)
                wide -> state.selected = e
                else -> open(e)
            }
        }

        fun longClick(e: Entry) {
            if (e.kind == Kind.DIR) return
            if (wide) state.selected = e else dialogEntry = e
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        if (state.searching) {
                            SearchField(state)
                        } else {
                            Text(
                                Fs.volumeOf(state.dir, volumes)
                                    ?.takeIf { it.dir.absolutePath == state.dir.absolutePath }?.label
                                    ?: state.dir.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        if (state.searching) {
                            IconButton(onClick = { state.closeSearch() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Fechar busca")
                            }
                        } else if (!atVolumeRoot) {
                            IconButton(onClick = { state.dir.parentFile?.let { state.navigate(it) } }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Subir")
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
                                    SearchIndex.ensure(ctx, homeDir, force = true) { n -> state.indexing = n }
                                    state.indexing = null
                                    state.indexVersion++
                                }
                            }) { Icon(Icons.Filled.Refresh, "Reindexar") }
                        } else {
                            IconButton(onClick = { state.searching = true }) {
                                Icon(Icons.Filled.Search, "Buscar")
                            }
                            IconButton(onClick = { state.navigate(homeDir) }) {
                                Icon(Icons.Filled.Home, "Início")
                            }
                            IconButton(onClick = { state.toggleGrid() }) {
                                if (state.grid) {
                                    Icon(Icons.AutoMirrored.Filled.List, "Ver em lista")
                                } else {
                                    Icon(painterResource(R.drawable.ic_grid), "Ver em grade")
                                }
                            }
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Filled.MoreVert, "Mais")
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Definir esta pasta como Início") },
                                        onClick = {
                                            menuOpen = false
                                            state.setHomeHere()
                                            Toast.makeText(ctx, "Início: ${state.dir.name}", Toast.LENGTH_SHORT).show()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Atualizar pasta") },
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
                if (state.searching) {
                    SearchInfo(state, homeDir)
                } else {
                    Breadcrumbs(state.dir, volumes) { state.navigate(it) }
                }
                if (state.loading && !state.searching) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                }
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(if (wide) 0.58f else 1f).fillMaxHeight()) {
                        if (state.searching) {
                            ResultsList(state, ::click, ::longClick)
                        } else {
                            FolderContent(state, ::click, ::longClick)
                        }
                    }
                    if (wide) {
                        VerticalDivider()
                        Box(Modifier.weight(0.42f).fillMaxHeight()) {
                            PreviewPane(
                                entry = state.selected,
                                onOpen = { open(it) },
                                onShowFolder = if (state.searching) ({ f: File -> showInFolder(f) }) else null,
                            )
                        }
                    }
                }
            }
        }

        dialogEntry?.let { e ->
            Dialog(
                onDismissRequest = { dialogEntry = null },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(
                    Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.88f),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    PreviewPane(
                        entry = e,
                        onOpen = { dialogEntry = null; open(it) },
                        onShowFolder = if (state.searching) ({ f: File -> dialogEntry = null; showInFolder(f) }) else null,
                    )
                }
            }
        }
    }
}

private fun ctxPrefsSave(ctx: android.content.Context, d: File) {
    Prefs(ctx).lastDir = d.absolutePath
}

private fun appVersion(ctx: android.content.Context): String = try {
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
} catch (e: Exception) {
    "?"
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
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
}

@Composable
private fun SearchInfo(state: ExplorerState, homeDir: File) {
    val n = state.indexing
    val text = when {
        n != null -> "Indexando ${homeDir.name}… $n arquivos"
        state.query.isBlank() -> "Buscando em ${homeDir.name} (${SearchIndex.size} arquivos). Várias palavras: todas precisam aparecer."
        else -> "${state.results.size}${if (state.results.size >= 400) "+" else ""} resultados em ${homeDir.name}"
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
            columns = GridCells.Adaptive(132.dp),
            state = gs,
            contentPadding = PaddingValues(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(entries.size, key = { entries[it].path }) { i ->
                val e = entries[i]
                GridItem(e, state.selected?.path == e.path, { onClick(e) }, { onLong(e) })
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
                ListItemRow(e, null, state.selected?.path == e.path, { onClick(e) }, { onLong(e) })
            }
        }
    }
}

@Composable
private fun ResultsList(state: ExplorerState, onClick: (Entry) -> Unit, onLong: (Entry) -> Unit) {
    val home = state.home
    LazyColumn(Modifier.fillMaxSize()) {
        items(state.results, key = { it.path }) { e ->
            val folder = e.file.parent.orEmpty().let { p ->
                if (home != null) p.removePrefix(home).trimStart('/').ifEmpty { "(Início)" } else p
            }
            ListItemRow(e, folder, state.selected?.path == e.path, { onClick(e) }, { onLong(e) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridItem(e: Entry, selected: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Column(
        Modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            KindVisual(e, 320, Modifier.fillMaxSize(), iconSize = 56)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            e.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListItemRow(
    e: Entry,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    onLong: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 44.dp, height = 56.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            KindVisual(e, 120, Modifier.fillMaxSize(), iconSize = 28)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = subtitle ?: if (e.kind == Kind.DIR) "Pasta" else "${Fs.humanSize(e.size)} • ${Fs.humanDate(e.modified)}"
            Text(
                meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Miniatura para PDF/imagem, ícone para pasta e outros arquivos. */
@Composable
private fun KindVisual(e: Entry, thumbW: Int, modifier: Modifier, iconSize: Int) {
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
                if (value == null) value = Thumbs.load(ctx, e, thumbW)
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

/** Painel de pré-visualização: folheia as páginas sem abrir o leitor. */
@Composable
fun PreviewPane(entry: Entry?, onOpen: (Entry) -> Unit, onShowFolder: ((File) -> Unit)?) {
    if (entry == null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "Toque num arquivo para pré-visualizar.\nToque de novo para abrir.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val viewable = entry.kind == Kind.PDF || entry.kind == Kind.IMAGE
    val doc = remember(entry.path) { if (viewable) openDoc(entry.file) else null }
    DisposableEffect(doc) { onDispose { doc?.close() } }
    var page by remember(entry.path) { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { onOpen(entry) },
            contentAlignment = Alignment.Center,
        ) {
            val w = constraints.maxWidth
            val h = constraints.maxHeight
            when {
                doc != null -> {
                    val bmp by produceState<Bitmap?>(null, doc, page, w, h) {
                        value = doc.render(page, w, h)
                    }
                    val b = bmp
                    if (b != null) {
                        Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
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

        val pages = doc?.pageCount ?: 0
        if (pages > 1) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(enabled = page > 0, onClick = { page-- }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Página anterior")
                }
                Text("${page + 1} / $pages")
                IconButton(enabled = page < pages - 1, onClick = { page++ }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Próxima página")
                }
            }
        } else {
            Spacer(Modifier.height(8.dp))
        }

        Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(
            entry.file.parentFile?.name.orEmpty(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val kindLabel = when (entry.kind) {
            Kind.PDF -> "PDF • $pages ${if (pages == 1) "página" else "páginas"}"
            Kind.IMAGE -> "Imagem"
            else -> entry.file.extension.uppercase().ifEmpty { "Arquivo" }
        }
        Text(
            "$kindLabel • ${Fs.humanSize(entry.size)} • ${Fs.humanDate(entry.modified)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onOpen(entry) }) {
                Text(if (viewable) "Abrir" else "Abrir com outro app")
            }
            if (onShowFolder != null) {
                OutlinedButton(onClick = { onShowFolder(entry.file) }) { Text("Mostrar na pasta") }
            }
        }
    }
}
