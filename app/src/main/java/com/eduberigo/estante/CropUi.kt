package com.eduberigo.estante

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * Editor de recorte manual: mostra a página inteira e um retângulo para arrastar (cantos, lados ou o meio).
 * onApply(rect, todasAsPaginas, gravarNoArquivo); rect null = remover o recorte manual.
 */
@Composable
fun CropEditor(
    doc: Doc,
    index: Int,
    initial: RectF?,
    onCancel: () -> Unit,
    onApply: (RectF?, Boolean, Boolean) -> Unit,
) {
    var all by remember { mutableStateOf(false) }
    var rect by remember { mutableStateOf<RectF?>(initial?.let { RectF(it) }) }

    Box(Modifier.fillMaxSize().background(Color(0xFF111111))) {
        Column(Modifier.fillMaxSize()) {
            Text(
                "Arraste os cantos, os lados ou o meio do retângulo. Fora dele fica cortado.",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.statusBarsPadding().padding(12.dp),
            )
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(8.dp)) {
                val wPx = constraints.maxWidth
                val hPx = constraints.maxHeight
                val bmp by produceState<Bitmap?>(null, doc, index) {
                    val b = doc.render(index, wPx, hPx, false, false, null)
                    value = b
                    if (rect == null && b != null) rect = Crop.detect(b) ?: RectF(0.03f, 0.03f, 0.97f, 0.97f)
                }
                val b = bmp
                val r = rect
                if (b == null || r == null) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else {
                    val k = minOf(wPx.toFloat() / b.width, hPx.toFloat() / b.height)
                    val ox = (wPx - b.width * k) / 2f
                    val oy = (hPx - b.height * k) / 2f
                    val iw = b.width * k
                    val ih = b.height * k
                    val rectNow by rememberUpdatedState(r)
                    Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    Canvas(
                        Modifier.fillMaxSize().pointerInput(b) {
                            // Qual parte está sendo arrastada: 0 = mover; bits 1,2,4,8 = esquerda, topo, direita, base.
                            var grab = 0
                            detectDragGestures(
                                onDragStart = { p ->
                                    val q = rectNow
                                    val l = ox + q.left * iw
                                    val t = oy + q.top * ih
                                    val rr = ox + q.right * iw
                                    val bb = oy + q.bottom * ih
                                    val tol = 60f
                                    grab = 0
                                    if (abs(p.x - l) < tol && p.y in t - tol..bb + tol) grab = grab or 1
                                    if (abs(p.y - t) < tol && p.x in l - tol..rr + tol) grab = grab or 2
                                    if (abs(p.x - rr) < tol && p.y in t - tol..bb + tol) grab = grab or 4
                                    if (abs(p.y - bb) < tol && p.x in l - tol..rr + tol) grab = grab or 8
                                },
                            ) { change, d ->
                                change.consume()
                                val dx = d.x / iw
                                val dy = d.y / ih
                                val q = RectF(rectNow)
                                if (grab == 0) {
                                    val mx = dx.coerceIn(-q.left, 1f - q.right)
                                    val my = dy.coerceIn(-q.top, 1f - q.bottom)
                                    q.offset(mx, my)
                                } else {
                                    if (grab and 1 != 0) q.left = (q.left + dx).coerceIn(0f, q.right - 0.05f)
                                    if (grab and 2 != 0) q.top = (q.top + dy).coerceIn(0f, q.bottom - 0.05f)
                                    if (grab and 4 != 0) q.right = (q.right + dx).coerceIn(q.left + 0.05f, 1f)
                                    if (grab and 8 != 0) q.bottom = (q.bottom + dy).coerceIn(q.top + 0.05f, 1f)
                                }
                                rect = q
                            }
                        }
                    ) {
                        val l = ox + r.left * iw
                        val t = oy + r.top * ih
                        val rr = ox + r.right * iw
                        val bb = oy + r.bottom * ih
                        val shade = Color.Black.copy(alpha = 0.55f)
                        drawRect(shade, Offset(ox, oy), Size(iw, t - oy))
                        drawRect(shade, Offset(ox, bb), Size(iw, oy + ih - bb))
                        drawRect(shade, Offset(ox, t), Size(l - ox, bb - t))
                        drawRect(shade, Offset(rr, t), Size(ox + iw - rr, bb - t))
                        drawRect(Color(0xFF8AB4F8), Offset(l, t), Size(rr - l, bb - t), style = Stroke(4f))
                        listOf(Offset(l, t), Offset(rr, t), Offset(l, bb), Offset(rr, bb)).forEach {
                            drawCircle(Color(0xFF8AB4F8), 16f, it)
                        }
                    }
                }
            }
            Surface(color = Color(0xF0202124), modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !all, onClick = { all = false })
                        Text("Só esta página", color = Color.White, modifier = Modifier.clickable { all = false })
                        RadioButton(selected = all, onClick = { all = true })
                        Text("Todas as páginas", color = Color.White, modifier = Modifier.clickable { all = true })
                    }
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onCancel) { Text("Cancelar", color = Color.White) }
                        TextButton(onClick = { onApply(null, all, false) }) { Text("Remover recorte manual", color = Color.White) }
                        OutlinedButton(enabled = rect != null, onClick = { onApply(rect, all, true) }) {
                            Text("Gravar no arquivo…", color = Color.White)
                        }
                        Button(enabled = rect != null, onClick = { onApply(rect, all, false) }) { Text("Só na tela") }
                    }
                }
            }
        }
    }
}

/** Gravar o recorte: cópia "(recortado)" ou substituir (original vai para a Estante-Lixeira). */
@Composable
fun CropFileDialog(
    files: List<java.io.File>,
    rectFor: (java.io.File, Int) -> RectF?,
    volumes: List<Volume>,
    lib: Library,
    onDone: (Map<String, String>) -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var replace by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }
    var links by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!running) onDone(links) },
        title = { Text("Gravar recorte no arquivo") },
        text = {
            Column {
                val r = report
                if (r != null) {
                    Text(r)
                } else {
                    Text(
                        "${files.size} arquivo(s). Em PDF, a qualidade não muda (só a área visível). " +
                            "Anotações já feitas podem ficar deslocadas depois do recorte.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(Modifier.clickable { replace = false }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !replace, onClick = { replace = false })
                        Text("Salvar cópia \"(recortado)\"")
                    }
                    Row(Modifier.clickable { replace = true }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = replace, onClick = { replace = true })
                        Text("Substituir o original (vai para a Estante-Lixeira)")
                    }
                    if (running) CircularProgressIndicator(Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            if (report != null) {
                TextButton(onClick = { onDone(links) }) { Text("OK") }
            } else {
                TextButton(enabled = !running, onClick = {
                    running = true
                    scope.launch {
                        var ok = 0
                        val fail = mutableListOf<String>()
                        val map = LinkedHashMap<String, String>()
                        for (src in files) {
                            try {
                                val dir = src.parentFile!!
                                val ext = src.extension.lowercase()
                                val out = if (replace) java.io.File(dir, ".estante-tmp-${System.nanoTime()}.$ext")
                                else Export.unique(dir, "${src.nameWithoutExtension} (recortado)", ext)
                                Export.cropFile(src, out) { i -> rectFor(src, i) }
                                if (replace) {
                                    val t = FileOps.trash(listOf(src.absolutePath), volumes)
                                    if (t.done != 1) throw IllegalStateException("não moveu o original para a lixeira")
                                    if (!out.renameTo(src)) throw IllegalStateException("não renomeou")
                                } else {
                                    map[src.absolutePath] = out.absolutePath
                                }
                                ok++
                            } catch (e: Throwable) {
                                fail.add(src.name + " (" + (e.message ?: "erro") + ")")
                            }
                        }
                        links = if (replace) emptyMap() else map
                        running = false
                        report = "Prontos: $ok" + if (fail.isNotEmpty()) "\nFalharam: ${fail.joinToString()}" else "" +
                            if (!replace && ok > 0) "\nA cópia fica ao lado do original." else ""
                    }
                }) { Text(if (running) "Gravando…" else "Gravar") }
            }
        },
        dismissButton = { if (report == null) TextButton(enabled = !running, onClick = { onDone(emptyMap()) }) { Text("Cancelar") } },
    )
}
