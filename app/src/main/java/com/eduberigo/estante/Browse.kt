package com.eduberigo.estante

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Tipos de navegação por informação tirada do nome do arquivo. */
enum class BrowseKind(val label: String, val plural: String) {
    ARTIST("Artista", "Artistas"), TITLE("Título", "Títulos"), GENRE("Gênero", "Gêneros"), KEY("Tom", "Tons"),
}

data class BrowseItem(val path: String, val info: Meta.Info, val artists: List<String>)

/** Lê (uma vez por versão do índice) as informações de todos os PDFs das pastas do Início. */
object BrowseData {
    @Volatile private var version = -1
    @Volatile private var data: List<BrowseItem> = emptyList()

    fun get(indexVersion: Int): List<BrowseItem> {
        if (version != indexVersion || data.isEmpty()) {
            data = SearchIndex.allPaths()
                .filter { it.endsWith(".pdf", ignoreCase = true) }
                .map {
                    val inf = Meta.info(File(it).nameWithoutExtension)
                    BrowseItem(it, inf, inf.artist?.let { a -> Meta.artistsOf(a) }.orEmpty())
                }
            buildNames()
            version = indexVersion
        }
        return data
    }

    /** Nome de exibição de cada grupo (chave sem acento/maiúsculas → grafia mais comum, preferindo com maiúsculas). */
    @Volatile private var display: Map<String, String> = emptyMap()

    private fun buildNames() {
        val variants = HashMap<String, MutableMap<String, Int>>()
        for (it in data) {
            val names = it.artists + listOfNotNull(it.info.genre, it.info.key)
            for (n in names) {
                val m = variants.getOrPut(Meta.key(n)) { HashMap() }
                m[n] = (m[n] ?: 0) + 1
            }
        }
        display = variants.mapValues { (_, m) ->
            val best = m.entries.sortedWith(
                compareByDescending<Map.Entry<String, Int>> { e -> e.key.any { it.isUpperCase() } }.thenByDescending { it.value }
            ).first().key
            if (best.any { it.isUpperCase() }) best else best.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        }
    }

    fun nameOf(raw: String): String = display[Meta.key(raw)] ?: raw

    /** Valores de agrupamento de um item (um item pode estar em vários artistas, por causa das participações). */
    fun valuesOf(kind: BrowseKind, it: BrowseItem): List<String> = when (kind) {
        BrowseKind.ARTIST -> it.artists.map { a -> nameOf(a) }.distinct().ifEmpty { listOf("(sem artista no nome)") }
        BrowseKind.TITLE -> listOf(it.info.title)
        BrowseKind.GENRE -> listOf(it.info.genre?.let { g -> nameOf(g) } ?: "(sem gênero no nome)")
        BrowseKind.KEY -> listOf(it.info.key?.let { k -> nameOf(k) } ?: "(sem tom no nome)")
    }
}

/**
 * Navegar por artista / título / gênero / tom. As informações vêm do nome do arquivo
 * (estimativa: quem se repete muito é artista). Cópias em pastas diferentes aparecem cada uma.
 */
@Composable
fun BrowseContent(
    state: ExplorerState,
    kind: BrowseKind,
    group: String?,
    onGroup: (String) -> Unit,
    onClick: (Entry) -> Unit,
    onLong: (Entry) -> Unit,
) {
    var filter by remember(kind, group) { mutableStateOf("") }
    val items by produceState<List<BrowseItem>?>(null, kind, state.indexVersion) {
        value = withContext(Dispatchers.Default) { BrowseData.get(state.indexVersion) }
    }
    val all = items
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            singleLine = true,
            placeholder = { Text(if (kind == BrowseKind.TITLE || group != null) "Filtrar músicas…" else "Filtrar ${kind.plural.lowercase()}…") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )
        if (all == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        if (all.isEmpty()) {
            HintText("Nada indexado ainda. Adicione pastas ao Início e aguarde a indexação (abra a busca uma vez).")
            return@Column
        }
        val f = Fs.norm(filter.trim())
        if (kind == BrowseKind.TITLE || group != null) {
            // Lista de músicas.
            // Filtrar e ordenar milhares de itens em segundo plano (a tela continua respondendo ao toque).
            val list by produceState(emptyList<BrowseItem>(), all, kind, group, f) {
                value = withContext(Dispatchers.Default) {
                    val g = group?.let { Meta.key(it) }
                    all.asSequence()
                        .filter { g == null || BrowseData.valuesOf(kind, it).any { v -> Meta.key(v) == g } }
                        .filter { f.isEmpty() || Fs.norm(it.info.title + " " + (it.info.artist ?: "")).contains(f) }
                        .sortedWith(Comparator { a, b -> NaturalOrder.compare(a.info.title, b.info.title) })
                        .take(3000)
                        .toList()
                }
            }
            Text(
                "${list.size}${if (list.size >= 3000) "+" else ""} músicas" + (group?.let { " • $it" } ?: ""),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            val byPath = remember(list) { list.associateBy { it.path } }
            val entries by produceState(emptyList<Entry>(), list) {
                value = withContext(Dispatchers.IO) { list.map { Fs.entry(File(it.path)) } }
            }
            EntryRows(
                state = state,
                list = entries,
                sub = { e ->
                    val inf = byPath[e.path]?.info
                    listOfNotNull(
                        inf?.artist?.takeIf { kind != BrowseKind.ARTIST },
                        inf?.key?.let { k -> "tom $k" },
                        inf?.bpm?.let { b -> "$b bpm" },
                        state.shortFolder(e.file),
                    ).joinToString(" • ")
                },
                title = { e -> byPath[e.path]?.info?.title ?: e.title },
                highlight = if (f.isEmpty()) emptyList() else f.split(' ').filter { it.isNotBlank() },
                onClick = onClick,
                onLong = onLong,
            )
        } else {
            // Lista de grupos (artistas, gêneros, tons).
            val groups by produceState(emptyList<Map.Entry<String, Int>>(), all, kind, f) {
                value = withContext(Dispatchers.Default) {
                    all.flatMap { BrowseData.valuesOf(kind, it) }.groupingBy { it }.eachCount()
                        .filter { f.isEmpty() || Fs.norm(it.key).contains(f) }
                        .entries
                        .sortedWith(Comparator { a, b ->
                            val sa = a.key.startsWith("(")
                            val sb = b.key.startsWith("(")
                            if (sa != sb) (if (sa) 1 else -1) else NaturalOrder.compare(a.key, b.key)
                        })
                }
            }
            Text(
                "${groups.size} ${kind.plural.lowercase()} • estimado pelo nome dos arquivos",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(groups.size, key = { groups[it].key }) { i ->
                    val (name, count) = groups[i]
                    Row(
                        Modifier.fillMaxWidth().clickable { onGroup(name) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text("$count", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
