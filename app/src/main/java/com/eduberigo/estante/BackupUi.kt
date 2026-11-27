package com.eduberigo.estante

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Process
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun BackupDialog(state: ExplorerState, volumes: List<Volume>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prog by LibraryBackup.progress.collectAsState()
    var msg by remember { mutableStateOf<String?>(null) }
    var restoreList by remember { mutableStateOf(false) }

    val pinned = state.pinned.map { File(it) }.filter { it.isDirectory }
    var chosen by remember { mutableStateOf(pinned.map { it.absolutePath }.toSet()) }
    var destIdx by remember { mutableIntStateOf(if (volumes.size > 1) 1 else 0) }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // Tamanho total das pastas escolhidas (calculado em segundo plano).
    val size by produceState<Long?>(null, chosen) {
        value = null
        value = withContext(Dispatchers.IO) {
            chosen.sumOf { p -> File(p).walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        }
    }
    val destDir = File(volumes.getOrElse(destIdx) { volumes.first() }.dir, "Documents/Estante/Backups")
    val free = remember(destIdx) { volumes.getOrElse(destIdx) { volumes.first() }.dir.usableSpace }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.94f), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                Text("Backup", style = MaterialTheme.typography.titleLarge)

                // ---------- dados do app ----------
                Text("Dados do app", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                Text(
                    "Tudo menos as partituras: setlists, histórico do que você tocou, estrelas, anotações, âncoras, " +
                        "saltos, recortes, metrônomo e ajustes de imagem de cada partitura, o que está fora do app, " +
                        "pastas do Início, favoritos e configurações. Arquivo pequeno, em Documentos/Estante/Backups.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            msg = try {
                                val f = AppBackup.create(ctx)
                                "Backup do app salvo: ${f.name}"
                            } catch (e: Throwable) {
                                "Falhou: ${e.message}"
                            }
                        }
                    }) { Text("Fazer backup") }
                    OutlinedButton(onClick = { restoreList = true }) { Text("Restaurar…") }
                }
                msg?.let { Text(it, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp)) }

                HorizontalDivider(Modifier.padding(vertical = 16.dp))

                // ---------- partituras ----------
                Text("Partituras (arquivo .zip único)", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Junta as pastas escolhidas num .zip (os dados do app vão dentro). PDFs e imagens já são comprimidos, " +
                        "então o .zip fica quase do tamanho da biblioteca. Continua em segundo plano, com aviso na notificação.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (pinned.isEmpty()) {
                    Text(
                        "Adicione antes as pastas de partituras ao Início.",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                pinned.forEach { f ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !prog.running) {
                            chosen = if (f.absolutePath in chosen) chosen - f.absolutePath else chosen + f.absolutePath
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = f.absolutePath in chosen, onCheckedChange = null, enabled = !prog.running)
                        Text(state.shortFolder(File(f, "x")), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text("Salvar em", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                volumes.forEachIndexed { i, v ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !prog.running) { destIdx = i },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = destIdx == i, onClick = null, enabled = !prog.running)
                        Text("${v.label} • livre ${Fs.humanSize(v.dir.usableSpace)}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                val s = size
                val enough = s != null && free > s + 50L * 1024 * 1024
                Text(
                    when {
                        s == null -> "Calculando o tamanho…"
                        !enough -> "Tamanho: ${Fs.humanSize(s)} — espaço insuficiente aqui (livre ${Fs.humanSize(free)}). " +
                            "Escolha outro destino ou menos pastas."
                        else -> "Tamanho: ${Fs.humanSize(s)} • cabe no destino."
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (s != null && !enough) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )

                if (prog.running) {
                    val pct = if (prog.total > 0) prog.done.toFloat() / prog.total else 0f
                    Text(
                        "${(pct * 100).toInt()}% • ${Fs.humanSize(prog.done)} de ${Fs.humanSize(prog.total)} • ${prog.current}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
                prog.result?.let { Text(it, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 10.dp)) }

                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    if (prog.running) {
                        OutlinedButton(onClick = {
                            ctx.startService(Intent(ctx, BackupService::class.java).setAction("cancel"))
                        }) { Text("Cancelar backup") }
                    }
                    TextButton(onClick = onDismiss) { Text("Fechar") }
                    Button(
                        enabled = !prog.running && chosen.isNotEmpty() && enough,
                        onClick = {
                            if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                            LibraryBackup.start(ctx, chosen.toList(), destDir.absolutePath)
                        },
                    ) { Text("Iniciar backup") }
                }
            }
        }
    }

    if (restoreList) {
        val list = remember { AppBackup.list() }
        AlertDialog(
            onDismissRequest = { restoreList = false },
            title = { Text("Restaurar dados do app") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Substitui setlists, histórico, estrelas, anotações e configurações atuais pelos do backup. " +
                            "As partituras não são tocadas. O app reinicia em seguida.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (list.isEmpty()) Text("Nenhum backup em Documentos/Estante/Backups.", modifier = Modifier.padding(top = 8.dp))
                    list.forEach { f ->
                        Text(
                            "${f.name} • ${Fs.humanSize(f.length())}",
                            modifier = Modifier.fillMaxWidth().clickable {
                                scope.launch {
                                    try {
                                        AppBackup.restore(ctx, f)
                                        Toast.makeText(ctx, "Restaurado. Reiniciando…", Toast.LENGTH_SHORT).show()
                                        val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)!!
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                        ctx.startActivity(i)
                                        Process.killProcess(Process.myPid())
                                    } catch (e: Throwable) {
                                        Toast.makeText(ctx, "Falhou: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }.padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { restoreList = false }) { Text("Fechar") } },
        )
    }
}
