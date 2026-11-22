package com.eduberigo.estante

import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Salvar um trecho das imagens de um songbook (ex.: páginas 3 a 5) como um PDF novo.
 * Sugere a pasta do artista quando encontra uma pasta com o nome dele nas pastas do Início.
 */
@Composable
fun SaveImagesPdfDialog(files: List<File>, current: Int, lib: Library, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val folder = files.firstOrNull()?.parentFile
    val songbook = folder?.name ?: "Songbook"

    var from by remember { mutableStateOf((current + 1).toString()) }
    var to by remember { mutableStateOf((current + 1).toString()) }
    var name by remember { mutableStateOf(songbook) }
    var choice by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<File?>(null) }
    var addTo by remember { mutableStateOf(false) }

    // Destinos: pastas com o nome do artista (achadas nas pastas do Início), a pasta das imagens, Documentos/Estante.
    val options by produceState(emptyList<Pair<String, File>>(), songbook) {
        value = withContext(Dispatchers.Default) {
            val cands = mutableSetOf(songbook)
            songbook.split(" - ", " – ").map { it.trim() }.filter { it.isNotEmpty() }.forEach { cands.add(it) }
            Meta.guess(songbook).artist?.let { cands.add(it) }
            val artistDirs = SearchIndex.dirsNamed(cands)
                .filter { it.isDirectory && it.absolutePath != folder?.absolutePath }
            val out = mutableListOf<Pair<String, File>>()
            artistDirs.forEach { out.add("Pasta do artista: ${it.absolutePath.substringAfter("/0/")}" to it) }
            if (folder != null) out.add("Mesma pasta das imagens" to folder)
            out.add(
                "Documentos/Estante" to File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Estante"
                )
            )
            out
        }
    }

    if (addTo && saved != null) {
        AddToSetlistDialog(lib, listOf(saved!!.absolutePath)) { addTo = false; onDismiss() }
        return
    }

    Dialog(onDismissRequest = { if (!saving) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.92f), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                val done = saved
                if (done != null) {
                    Text("PDF salvo", style = MaterialTheme.typography.titleLarge)
                    Text(done.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                    Text(
                        done.parent.orEmpty().substringAfter("/0/"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        OutlinedButton(onClick = { addTo = true }) { Text("Adicionar a setlist") }
                        Button(onClick = onDismiss) { Text("OK") }
                    }
                    return@Column
                }

                Text("Salvar páginas como PDF", style = MaterialTheme.typography.titleLarge)
                Text(
                    "${files.size} imagens nesta pasta. Escolha o trecho.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = from, onValueChange = { from = it.filter(Char::isDigit) },
                        label = { Text("Da página") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(130.dp),
                    )
                    Text("até", modifier = Modifier.padding(horizontal = 12.dp))
                    OutlinedTextField(
                        value = to, onValueChange = { to = it.filter(Char::isDigit) },
                        label = { Text("Até a página") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(130.dp),
                    )
                }
                val a = from.toIntOrNull()
                val b = to.toIntOrNull()
                val valid = a != null && b != null && a in 1..files.size && b in a..files.size
                if (a != null && b != null && valid) {
                    Text(
                        "${b - a + 1} página(s): ${files[a - 1].nameWithoutExtension} … ${files[b - 1].nameWithoutExtension}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    Text(
                        "Use números de 1 a ${files.size}, com o início menor ou igual ao fim.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Nome do arquivo") }, singleLine = true,
                    supportingText = { Text("Depois você pode trocar pelo título da música.") },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )

                Text("Salvar em", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    options.forEachIndexed { i, (label, _) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { choice = i }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = choice == i, onClick = { choice = i })
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (options.none { it.first.startsWith("Pasta do artista") }) {
                    Text(
                        "Não achei uma pasta com o nome do artista nas pastas do Início.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (saving) CircularProgressIndicator(Modifier.padding(end = 8.dp))
                    TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancelar") }
                    Button(
                        enabled = valid && !saving && name.isNotBlank() && options.isNotEmpty(),
                        onClick = {
                            val dir = options[choice.coerceIn(0, options.lastIndex)].second
                            val pages = files.subList(a!! - 1, b!!)
                            saving = true
                            scope.launch {
                                try {
                                    dir.mkdirs()
                                    val out = Export.unique(dir, name, "pdf")
                                    Export.imagesToPdf(pages, out)
                                    saved = out
                                } catch (e: Throwable) {
                                    Toast.makeText(ctx, "Erro ao salvar: ${e.message}", Toast.LENGTH_LONG).show()
                                } finally {
                                    saving = false
                                }
                            }
                        },
                    ) { Text("Salvar PDF") }
                }
            }
        }
    }
}
