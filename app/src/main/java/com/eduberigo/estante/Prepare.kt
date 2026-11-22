package com.eduberigo.estante

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import java.io.File

/**
 * Preparar antes do show: grava o modo legível nos arquivos.
 * Cópia "(legível)" ao lado do original, ou substitui o original (que vai para a Estante-Lixeira).
 */
@Composable
fun PrepareFilesDialog(
    paths: List<String>,
    volumes: List<Volume>,
    lib: Library,
    autoCrop: Boolean,
    onRelink: (Map<String, String>) -> Unit,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val files = remember(paths) { paths.map { File(it) }.filter { it.isFile && Fs.kindOf(it) in setOf(Kind.PDF, Kind.IMAGE) } }
    var crop by remember { mutableStateOf(autoCrop) }
    var replace by remember { mutableStateOf(false) }
    var relinkCopies by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var frac by remember { mutableStateOf(0f) }
    var report by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = { if (!running) onDone() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.92f), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text("Gravar ajustes de imagem no arquivo", style = MaterialTheme.typography.titleLarge)
                val r = report
                if (r != null) {
                    Text(r, modifier = Modifier.padding(top = 12.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                        Button(onClick = onDone) { Text("OK") }
                    }
                    return@Column
                }
                Text(
                    "${files.size} arquivo(s). Usa os ajustes de imagem salvos de cada partitura " +
                        "(endireitar, fundo, preto, nitidez); sem ajustes salvos, usa o atalho Legível. " +
                        "O PDF resultante é feito de imagens das páginas (como uma digitalização).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
                )
                Row(Modifier.fillMaxWidth().clickable(enabled = !running) { crop = !crop }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = crop, onCheckedChange = { crop = it }, enabled = !running)
                    Text("Recortar as margens também")
                }
                Row(Modifier.fillMaxWidth().clickable(enabled = !running) { replace = false }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !replace, onClick = { replace = false }, enabled = !running)
                    Text("Salvar cópia \"(legível)\" ao lado do original")
                }
                if (!replace) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 32.dp).clickable(enabled = !running) { relinkCopies = !relinkCopies },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = relinkCopies, onCheckedChange = { relinkCopies = it }, enabled = !running)
                        Text("Usar a cópia nos setlists e favoritos", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(Modifier.fillMaxWidth().clickable(enabled = !running) { replace = true }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = replace, onClick = { replace = true }, enabled = !running)
                    Text("Substituir o original (o original vai para a Estante-Lixeira)")
                }
                if (running) {
                    Text(status, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
                    LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(enabled = !running, onClick = onDone) { Text("Cancelar") }
                    Button(enabled = !running && files.isNotEmpty(), onClick = {
                        running = true
                        scope.launch {
                            var ok = 0
                            val fail = mutableListOf<String>()
                            val links = LinkedHashMap<String, String>()
                            files.forEachIndexed { fi, src ->
                                try {
                                    val dir = src.parentFile!!
                                    val ext = if (Fs.kindOf(src) == Kind.PDF) "pdf" else "jpg"
                                    val tmp = if (replace) File(dir, ".estante-tmp-${System.nanoTime()}.$ext")
                                    else Export.unique(dir, "${src.nameWithoutExtension} (legível)", ext)
                                    val nt = AnnStore.load(ctx, src.absolutePath)
                                    val lk = nt.look?.takeIf { !it.isDefault } ?: Look.LEGIVEL
                                    Export.enhanceFile(src, tmp, crop, lk, nt) { p, n ->
                                        status = "${fi + 1}/${files.size}: ${src.nameWithoutExtension} — página $p de $n"
                                        frac = (fi + p.toFloat() / n) / files.size
                                    }
                                    if (replace) {
                                        val target = File(dir, src.nameWithoutExtension + "." + ext)
                                        val t = FileOps.trash(listOf(src.absolutePath), volumes)
                                        if (t.done != 1) throw IllegalStateException("não consegui mover o original para a lixeira")
                                        if (!tmp.renameTo(target)) throw IllegalStateException("não consegui renomear")
                                        if (target.absolutePath != src.absolutePath) links[src.absolutePath] = target.absolutePath
                                        // Os ajustes já estão gravados na imagem: não aplicar de novo.
                                        AnnStore.save(ctx, target.absolutePath, nt.copy(look = null, crops = emptyMap()))
                                    } else if (relinkCopies) {
                                        links[src.absolutePath] = tmp.absolutePath
                                    }
                                    ok++
                                } catch (e: Throwable) {
                                    fail.add(src.name + " (" + (e.message ?: "erro") + ")")
                                }
                            }
                            lib.relink(links)
                            AnnStore.relink(ctx, links)
                            onRelink(links)
                            running = false
                            report = "Prontos: $ok" + if (fail.isNotEmpty()) "\nFalharam: ${fail.joinToString()}" else ""
                        }
                    }) { Text("Aplicar") }
                }
            }
        }
    }
}
