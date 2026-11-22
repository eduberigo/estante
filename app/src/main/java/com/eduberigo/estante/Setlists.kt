package com.eduberigo.estante

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Conteúdo de um setlist (manual ou automático) na área da lista. */
@Composable
fun SetlistContent(
    state: ExplorerState,
    lib: Library,
    id: String,
    onClickItem: (List<Entry>, Int) -> Unit,
    onLongItem: (List<Entry>, Int) -> Unit,
    onPlay: (List<File>, Int) -> Unit,
    onDeleted: () -> Unit,
) {
    val ctx = LocalContext.current
    val manual = lib.get(id)
    val auto = if (manual == null) lib.autoSetlists().firstOrNull { it.id == id } else null
    val paths = manual?.items?.map { it.path } ?: auto?.items ?: emptyList()
    val labels = manual?.items?.map { it.label() } ?: paths.map { File(it).nameWithoutExtension }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var ordering by remember { mutableStateOf(false) }

    val entries by produceState(emptyList<Entry>(), paths) {
        value = withContext(Dispatchers.IO) {
            paths.map { p ->
                val f = File(p)
                if (f.exists()) Fs.entry(f) else Entry(f, Fs.kindOf(f, false), 0L, 0L)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                enabled = paths.isNotEmpty(),
                onClick = { onPlay(paths.map { File(it) }, 0) },
            ) { Text("▶ Tocar") }
            if (manual != null) {
                OutlinedButton(enabled = paths.size > 1, onClick = { ordering = !ordering }) {
                    Text(if (ordering) "Concluir ordem" else "Ordenar")
                }
                OutlinedButton(enabled = paths.isNotEmpty(), onClick = { exporting = true }) { Text("Exportar") }
                OutlinedButton(onClick = { renaming = true }) { Text("Renomear") }
                OutlinedButton(onClick = { deleting = true }) { Text("Excluir") }
            } else if (auto != null) {
                OutlinedButton(onClick = {
                    lib.create(auto.name, auto.items)
                    Toast.makeText(ctx, "Salvo como setlist \"${auto.name}\"", Toast.LENGTH_SHORT).show()
                }) { Text("Salvar como setlist") }
            }
        }
        Text(
            when {
                manual != null -> "${paths.size} músicas • segure o dedo numa música para mover, renomear ou remover"
                id == "auto:top" -> "Automático: as mais tocadas, da mais para a menos tocada"
                else -> "Automático: mais tocadas deste artista (artista estimado pelo nome do arquivo)"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        HorizontalDivider(Modifier.padding(top = 6.dp))
        if (paths.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Setlist vazio. Para adicionar músicas, segure o dedo sobre uma partitura " +
                        "(ou use o botão de setlist na pré-visualização) e escolha este setlist.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(entries.size, key = { "$it:${entries[it].path}" }) { i ->
                    val e = entries[i]
                    val plays = lib.playCount(e.path)
                    val sub = "${i + 1}. ${state.shortFolder(e.file)}" +
                        if (plays > 0) " • tocada ${plays}x" else ""
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            ListItemRow(
                                e, sub, state.isSel(e.path), state.isFav(e.path),
                                { onClickItem(entries, i) }, { onLongItem(entries, i) }, state.thumbSize,
                                title = labels.getOrNull(i) ?: e.title,
                            )
                        }
                        if (ordering && manual != null) {
                            androidx.compose.material3.FilledTonalIconButton(
                                enabled = i > 0,
                                onClick = { lib.move(manual.id, i, -1) },
                            ) { Text("▲") }
                            androidx.compose.material3.FilledTonalIconButton(
                                enabled = i < paths.lastIndex,
                                onClick = { lib.move(manual.id, i, 1) },
                                modifier = Modifier.padding(end = 8.dp),
                            ) { Text("▼") }
                        }
                    }
                }
            }
        }
    }

    if (renaming && manual != null) {
        NameDialog("Renomear setlist", manual.name, onDismiss = { renaming = false }) {
            lib.rename(manual.id, it)
            renaming = false
        }
    }
    if (exporting && manual != null) {
        ExportDialog(manual) { exporting = false }
    }
    if (deleting && manual != null) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Excluir \"${manual.name}\"?") },
            text = { Text("Só a lista é apagada. As partituras continuam onde estão.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    lib.delete(manual.id)
                    onDeleted()
                }) { Text("Excluir") }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancelar") } },
        )
    }
}

/** Escolher em qual setlist colocar a música (ou criar um novo). */
@Composable
fun AddToSetlistDialog(lib: Library, paths: List<String>, onDismiss: () -> Unit) {
    val many = paths.size > 1
    val what = if (many) "${paths.size} músicas adicionadas" else "Adicionada"
    val ctx = LocalContext.current
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        NameDialog("Novo setlist", "", onDismiss = { creating = false }) { name ->
            val s = lib.create(name, paths)
            Toast.makeText(ctx, "$what a \"${s.name}\"", Toast.LENGTH_SHORT).show()
            onDismiss()
        }
        return
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(vertical = 8.dp).fillMaxWidth()) {
                Text(
                    "Adicionar a setlist",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
                Text(
                    if (many) "${paths.size} músicas selecionadas" else File(paths.firstOrNull() ?: "").nameWithoutExtension,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                )
                HorizontalDivider()
                ActionRow("+ Novo setlist…") { creating = true }
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    lib.setlists.forEach { s ->
                        val has = !many && s.items.any { it.path == paths.firstOrNull() }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    paths.forEach { lib.add(s.id, it) }
                                    Toast.makeText(ctx, "$what a \"${s.name}\"", Toast.LENGTH_SHORT).show()
                                    onDismiss()
                                }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(painterResource(R.drawable.ic_setlist), null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                s.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 12.dp).weight(1f),
                            )
                            Text(
                                if (has) "já está • ${s.items.size}" else "${s.items.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Opções de uma música dentro de um setlist manual. */
@Composable
fun SetlistItemDialog(
    lib: Library,
    setlistId: String,
    index: Int,
    entry: Entry,
    onShowFolder: () -> Unit,
    onAddOther: () -> Unit,
    onDismiss: () -> Unit,
) {
    val s = lib.get(setlistId)
    var renamingItem by remember { mutableStateOf(false) }
    if (renamingItem && s != null) {
        NameDialog("Nome no setlist", s.items.getOrNull(index)?.label() ?: entry.title, onDismiss = onDismiss) {
            lib.renameItem(setlistId, index, it)
            onDismiss()
        }
        return
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(vertical = 8.dp).fillMaxWidth()) {
                Text(
                    "${index + 1}. ${s?.items?.getOrNull(index)?.label() ?: entry.title}",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
                HorizontalDivider()
                if (s != null) {
                    if (index > 0) {
                        ActionRow("Mover para cima") { lib.move(setlistId, index, -1); onDismiss() }
                        ActionRow("Mover para o início") { lib.move(setlistId, index, -index); onDismiss() }
                    }
                    if (index < s.items.lastIndex) {
                        ActionRow("Mover para baixo") { lib.move(setlistId, index, 1); onDismiss() }
                        ActionRow("Mover para o fim") { lib.move(setlistId, index, s.items.lastIndex - index); onDismiss() }
                    }
                    ActionRow("Renomear só no setlist…") { renamingItem = true }
                    if (s.items.getOrNull(index)?.name != null) {
                        ActionRow("Voltar ao nome do arquivo") { lib.renameItem(setlistId, index, null); onDismiss() }
                    }
                    ActionRow("Remover deste setlist") { lib.removeAt(setlistId, index); onDismiss() }
                }
                ActionRow("Adicionar a outro setlist") { onAddOther() }
                ActionRow("Mostrar na pasta") { onShowFolder() }
            }
        }
    }
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("Ex.: Casamento Ana e Pedro") },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Exportar o setlist: PDFs separados, fichário único, lista em texto. */
@Composable
fun ExportDialog(s: Setlist, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<Export.Result?>(null) }
    Dialog(onDismissRequest = { if (busy == null) onDismiss() }) {
        Surface(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(vertical = 8.dp).fillMaxWidth()) {
                Text(
                    "Exportar \"${s.name}\"",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
                val r = result
                when {
                    busy != null -> Row(
                        Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.CircularProgressIndicator()
                        Text(busy!!, modifier = Modifier.padding(start = 16.dp))
                    }
                    r != null -> {
                        Text(
                            "Salvo em Documentos/Estante/${Export.safe(s.name)}\n" +
                                "${r.files.size} arquivo(s)" +
                                if (r.skipped.isNotEmpty()) "\nNão exportadas: ${r.skipped.joinToString()}" else "",
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(20.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        ) {
                            OutlinedButton(onClick = { Export.shareFiles(ctx, r.files) }) { Text("Compartilhar") }
                            Button(onClick = onDismiss) { Text("OK") }
                        }
                    }
                    else -> {
                        HorizontalDivider()
                        ActionRow("PDFs separados (01 - música.pdf, 02 - …)") {
                            busy = "Gerando PDFs…"
                            scope.launch {
                                result = try { Export.separate(ctx, s) } catch (e: Throwable) { Export.Result(emptyList(), listOf(e.message ?: "erro")) }
                                busy = null
                            }
                        }
                        ActionRow("Fichário: tudo num PDF só, com índice") {
                            busy = "Montando o fichário…"
                            scope.launch {
                                result = try { Export.binder(ctx, s) } catch (e: Throwable) { Export.Result(emptyList(), listOf(e.message ?: "erro")) }
                                busy = null
                            }
                        }
                        ActionRow("Lista em texto (.txt)") {
                            scope.launch {
                                val f = try { Export.txt(s) } catch (e: Throwable) { null }
                                result = Export.Result(listOfNotNull(f), emptyList())
                            }
                        }
                        ActionRow("Enviar a lista como mensagem") {
                            Export.shareText(ctx, "Setlist ${s.name}", Export.text(s))
                        }
                    }
                }
            }
        }
    }
}
