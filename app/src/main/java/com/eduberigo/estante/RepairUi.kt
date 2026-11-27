package com.eduberigo.estante

import android.widget.Toast
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Aviso no Início, acima dos setlists. */
@Composable
fun RepairNotice(onClick: () -> Unit) {
    val miss = SetlistRepair.missing.size
    val sw = SetlistRepair.swaps.size
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp).clickable(onClick = onClick),
    ) {
        Text(
            listOfNotNull(
                if (miss > 0) "⚠ $miss música(s) de setlist sem arquivo" else null,
                if (sw > 0) "$sw trocada(s) sozinha(s) por outra cópia" else null,
            ).joinToString(" • ") + " — ver e substituir",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

private fun shortPath(p: String): String =
    p.substringAfter("/emulated/0/").substringAfter("/storage/").substringBeforeLast('/').replace("/", " › ")

/** "Setlists: músicas para conferir": as não encontradas (substituir) e as trocadas sozinhas (conferir). */
@Composable
fun RepairDialog(onReplace: (Missing) -> Unit, onReplaceSwap: (Swap) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val missing = SetlistRepair.missing
    val swaps = SetlistRepair.swaps
    var confirmNotes by remember { mutableStateOf<Swap?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.94f), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(
                    "Setlists: músicas para conferir",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
                HorizontalDivider()
                val scroll = rememberScrollState()
                Column(Modifier.heightIn(max = 520.dp).scrollBar(scroll).verticalScroll(scroll).padding(end = 10.dp)) {
                    if (missing.isEmpty() && swaps.isEmpty()) {
                        Text("Nada para conferir: todas as músicas dos setlists têm arquivo.", modifier = Modifier.padding(20.dp))
                    }
                    if (missing.isNotEmpty()) {
                        SectionHeader("Sem arquivo e sem outra cópia no acervo (${missing.size})")
                        missing.forEach { m ->
                            Row(
                                Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("${m.index + 1}. ${m.label}", style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "Setlist \"${m.setlist}\" • era: ${shortPath(m.old)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                TextButton(onClick = { onReplace(m) }) { Text("Substituir…") }
                            }
                        }
                    }
                    if (swaps.isNotEmpty()) {
                        SectionHeader("Trocadas sozinhas por outra cópia do mesmo arquivo (${swaps.size})")
                        swaps.asReversed().forEach { s ->
                            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
                                Text(s.label, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "Setlist \"${s.setlist}\"\nde: ${shortPath(s.old)}\npara: ${shortPath(s.new)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (s.oldNotes) {
                                        TextButton(onClick = { confirmNotes = s }) { Text("Trazer as anotações da cópia apagada") }
                                    }
                                    TextButton(onClick = { onReplaceSwap(s) }) { Text("Escolher outro arquivo…") }
                                }
                            }
                        }
                    }
                    if (SetlistRepair.txtFile.exists()) {
                        Text(
                            "A mesma lista fica em Documentos/Estante/${SetlistRepair.txtFile.name} (atualizada sozinha).",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    if (swaps.isNotEmpty()) TextButton(onClick = { SetlistRepair.clearSwaps() }) { Text("OK, conferi as trocas") }
                    TextButton(onClick = onDismiss) { Text("Fechar") }
                }
            }
        }
    }
    confirmNotes?.let { s ->
        val newHas = remember(s) { !AnnStore.load(ctx, s.new).isEmpty() }
        AlertDialog(
            onDismissRequest = { confirmNotes = null },
            title = { Text("Trazer as anotações?") },
            text = {
                Text(
                    "As anotações, âncoras, saltos e recortes da cópia apagada passam para a cópia em " +
                        "${shortPath(s.new)}." +
                        if (newHas) "\n\nAtenção: essa cópia já tem anotações próprias, que serão substituídas." else ""
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    SetlistRepair.copyNotes(ctx, s)
                    confirmNotes = null
                    Toast.makeText(ctx, "Anotações trazidas", Toast.LENGTH_SHORT).show()
                }) { Text(if (newHas) "Substituir" else "Trazer") }
            },
            dismissButton = { TextButton(onClick = { confirmNotes = null }) { Text("Cancelar") } },
        )
    }
}
