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

data class BrowseItem(val path: String, val info: Meta.Info)

/** Lê (uma vez por versão do índice) as informações de todos os PDFs das pastas do Início. */
object BrowseData {
    @Volatile private var version = -1
    @Volatile private var data: List<BrowseItem> = emptyList()

    fun get(indexVersion: Int): List<BrowseItem> {
        if (version != indexVersion || data.isEmpty()) {
            data = SearchIndex.allPaths()
                .filter { it.endsWith(".pdf", ignoreCase = true) }
                .map { BrowseItem(it, Meta.info(File(it).nameWithoutExtension)) }
            version = indexVersion
        }
        return data
    }

    fun valueOf(kind: BrowseKind, i: Meta.Info): String = when (kind) {
        BrowseKind.ARTIST -> i.artist ?: "(sem artista no nome)"
        BrowseKind.TITLE -> i.title
        BrowseKind.GENRE -> i.genre ?: "(sem gênero no nome)"
        BrowseKind.KEY -> i.key ?: "(sem tom no nome)"
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
            val list = remember(all, kind, group, f) {
                all.asSequence()
                    .filter { group == null || BrowseData.valueOf(kind, it.info) == group }
                    .filter { f.isEmpty() || Fs.norm(it.info.title + " " + (it.info.artist ?: "")).contains(f) }
                    .sortedWith(Comparator { a, b -> NaturalOrder.compare(a.info.title, b.info.title) })
                    .take(3000)
                    .toList()
            }
            Text(
                "${list.size}${if (list.size >= 3000) "+" else ""} músicas" + (group?.let { " • $it" } ?: ""),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(list.size, key = { list[it].path }) { i ->
                    val it = list[i]
                    val e = Entry(File(it.path), Kind.PDF, 0L, 0L)
                    val extra = listOfNotNull(
                        it.info.artist?.takeIf { kind != BrowseKind.ARTIST },
                        it.info.key?.let { k -> "tom $k" },
                        it.info.bpm?.let { b -> "$b bpm" },
                        state.shortFolder(e.file),
                    ).joinToString(" • ")
                    ListItemRow(
                        e, extra, state.isSel(e.path), state.isFav(e.path),
                        { onClick(Fs.entry(e.file)) }, { onLong(Fs.entry(e.file)) }, state.thumbSize,
                        title = it.info.title,
                    )
                }
            }
        } else {
            // Lista de grupos (artistas, gêneros, tons).
            val groups = remember(all, kind, f) {
                all.groupingBy { BrowseData.valueOf(kind, it.info) }.eachCount()
                    .filter { f.isEmpty() || Fs.norm(it.key).contains(f) }
                    .entries
                    .sortedWith(Comparator { a, b ->
                        val sa = a.key.startsWith("(")
                        val sb = b.key.startsWith("(")
                        if (sa != sb) (if (sa) 1 else -1) else NaturalOrder.compare(a.key, b.key)
                    })
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
