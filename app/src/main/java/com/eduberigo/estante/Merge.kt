package com.eduberigo.estante

import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import java.io.File

/**
 * Barra de rolagem discreta, sempre visível quando há mais conteúdo do que cabe:
 * mostra que a lista continua. Use antes de verticalScroll(scroll) no mesmo elemento.
 */
fun Modifier.scrollBar(scroll: ScrollState): Modifier = composed {
    val thumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    drawWithContent {
        drawContent()
        val max = scroll.maxValue
        if (max <= 0 || max == Int.MAX_VALUE) return@drawWithContent
        val h = size.height
        val w = 4.dp.toPx()
        val x = size.width - w - 3.dp.toPx()
        val thumb = (h * h / (h + max)).coerceAtLeast(32.dp.toPx()).coerceAtMost(h)
        val y = (h - thumb) * scroll.value / max
        drawRoundRect(trackColor, topLeft = Offset(x, 0f), size = Size(w, h), cornerRadius = CornerRadius(w / 2))
        drawRoundRect(thumbColor, topLeft = Offset(x, y), size = Size(w, thumb), cornerRadius = CornerRadius(w / 2))
    }
}

/** Pedido para abrir um arquivo no leitor (ex.: o PDF que acabou de ser criado). */
object OpenRequest {
    var file by mutableStateOf<File?>(null)
}

/**
 * Juntar os arquivos selecionados (PDFs e imagens) em um PDF novo, na ordem escolhida,
 * com um marcador (índice) por arquivo. Os originais não mudam.
 */
@Composable
fun MergePdfDialog(paths: List<String>, lib: Library, onCreated: (File) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val files = remember {
        mutableStateListOf<File>().apply {
            addAll(paths.map(::File).filter { it.isFile && Fs.kindOf(it, false) in setOf(Kind.PDF, Kind.IMAGE) })
        }
    }
    val ignored = remember { paths.size - files.size }
    val first = files.firstOrNull()
    var name by remember {
        val sameFolder = files.map { it.parent }.distinct().size == 1
        val allImages = files.all { Fs.kindOf(it, false) == Kind.IMAGE }
        mutableStateOf(
            when {
                first == null -> "PDF"
                allImages && sameFolder -> first.parentFile?.name ?: first.nameWithoutExtension
                files.size == 1 -> first.nameWithoutExtension
                else -> "${first.nameWithoutExtension} + ${files.size - 1}"
            }
        )
    }
    val docs = remember { File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Estante") }
    var dest by remember { mutableIntStateOf(0) }
    val autoCrop = remember { Prefs(ctx).autoCrop }
    var withNotes by remember { mutableStateOf(true) }
    var withCrops by remember { mutableStateOf(true) }
    var withLook by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var created by remember { mutableStateOf<File?>(null) }
    var skipped by remember { mutableStateOf<List<String>>(emptyList()) }
    var addTo by remember { mutableStateOf(false) }

    val done = created
    if (addTo && done != null) {
        AddToSetlistDialog(lib, listOf(done.absolutePath)) { addTo = false; onDismiss() }
        return
    }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.92f), shape = RoundedCornerShape(16.dp)) {
            val outer = rememberScrollState()
            Column(Modifier.padding(20.dp).scrollBar(outer).verticalScroll(outer)) {
                if (done != null) {
                    Text("PDF criado. Quer abrir agora?", style = MaterialTheme.typography.titleLarge)
                    Text(done.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                    Text(
                        done.parent.orEmpty().substringAfter("/0/"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (skipped.isNotEmpty()) {
                        Text(
                            "Não entraram (não consegui ler): ${skipped.joinToString()}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = { addTo = true }) { Text("Pôr em setlist") }
                        TextButton(onClick = onDismiss) { Text("Agora não") }
                        Button(onClick = { OpenRequest.file = done; onDismiss() }) { Text("Abrir") }
                    }
                    return@Column
                }

                Text("Juntar em um PDF", style = MaterialTheme.typography.titleLarge)
                Text(
                    "${files.size} arquivo(s), nesta ordem. Use ▲ ▼ para mudar a ordem." +
                        if (ignored > 0) " ($ignored pasta(s) ou item(ns) que não são PDF/imagem ficaram de fora.)" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                val inner = rememberScrollState()
                Column(Modifier.heightIn(max = 260.dp).scrollBar(inner).verticalScroll(inner)) {
                    files.forEachIndexed { i, f ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                            Text(
                                "${i + 1}. ${f.nameWithoutExtension}",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(enabled = i > 0, onClick = { files.add(i - 1, files.removeAt(i)) }) { Text("▲") }
                            TextButton(enabled = i < files.lastIndex, onClick = { files.add(i + 1, files.removeAt(i)) }) { Text("▼") }
                            TextButton(enabled = files.size > 1, onClick = { files.removeAt(i) }) { Text("✕") }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Nome do PDF novo") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Salvar em", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                val options = listOfNotNull(
                    first?.parentFile?.let { "Pasta do primeiro arquivo (${it.name})" to it },
                    "Documentos/Estante" to docs,
                )
                options.forEachIndexed { i, (label, _) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { dest = i },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = dest == i, onClick = { dest = i })
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text("Gravar dentro do PDF", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                MergeCheck("Anotações, âncoras e saltos", withNotes) { withNotes = it }
                MergeCheck("Recortes${if (autoCrop) " (manuais e automáticos)" else " manuais"}", withCrops) { withCrops = it }
                MergeCheck("Ajustes de imagem (a página vira imagem)", withLook) { withLook = it }

                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp))
                    TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancelar") }
                    Button(
                        enabled = !busy && files.isNotEmpty() && name.isNotBlank(),
                        onClick = {
                            val dir = options[dest.coerceIn(0, options.lastIndex)].second
                            val list = files.toList()
                            busy = true
                            scope.launch {
                                try {
                                    dir.mkdirs()
                                    val out = Export.unique(dir, name, "pdf")
                                    val r = Export.merge(
                                        ctx, list.map { it to it.nameWithoutExtension }, out,
                                        Flatten.Opts(withNotes, withCrops, withLook, autoCrop),
                                    )
                                    skipped = r.skipped
                                    created = out
                                    onCreated(out)
                                } catch (e: Throwable) {
                                    Toast.makeText(ctx, "Não foi possível criar o PDF: ${e.message}", Toast.LENGTH_LONG).show()
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    ) { Text("Criar PDF") }
                }
            }
        }
    }
}

@Composable
private fun MergeCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Lista do que está fora do app, com "Trazer de volta". */
@Composable
fun ExcludedDialog(onBringBack: (List<String>) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val vols = remember { Fs.volumes(ctx) }
    val items = Excluded.set.sortedWith(NaturalOrder)
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(vertical = 8.dp).fillMaxWidth()) {
                Text(
                    "Fora do app",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
                Text(
                    "Continuam no aparelho, mas não aparecem nas pastas, na busca, na navegação por artista/título " +
                        "nem na classificação do app.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                )
                HorizontalDivider()
                if (items.isEmpty()) {
                    Text(
                        "Nada fora do app. Para tirar uma pasta ou arquivo: segure o dedo nele → Opções → " +
                            "Tirar do app.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(20.dp),
                    )
                } else {
                    val scroll = rememberScrollState()
                    Column(Modifier.heightIn(max = 420.dp).scrollBar(scroll).verticalScroll(scroll)) {
                        items.forEach { p ->
                            val f = File(p)
                            val vol = Fs.volumeOf(f, vols)
                            val where = (vol?.label ?: "") + (f.parent.orEmpty().removePrefix(vol?.dir?.absolutePath ?: "")
                                .replace("/", " › "))
                            Row(
                                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        (if (f.isDirectory) "📁 " else "") + f.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        where + if (!f.exists()) " (não existe mais)" else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                TextButton(onClick = { onBringBack(listOf(p)) }) { Text("Trazer de volta") }
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    if (items.size > 1) TextButton(onClick = { onBringBack(items) }) { Text("Trazer todos") }
                    TextButton(onClick = onDismiss) { Text("Fechar") }
                }
            }
        }
    }
}
