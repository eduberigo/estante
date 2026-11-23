package com.eduberigo.estante

import android.content.Context
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.math.hypot

/**
 * Anotações de um arquivo. Ficam num JSON do app (nunca dentro do PDF) e são por caminho:
 * a mesma música em duas pastas tem anotações independentes.
 * Coordenadas em frações da página inteira (0..1), então não mudam com recorte ou zoom.
 *
 * kind: "pen" (caneta), "hl" (marca-texto), "circle" (pts = l,t,r,b), "text" e "finger" (pts = x,y).
 * w = espessura (ou tamanho do texto) em fração da largura da página.
 */
data class Ann(val kind: String, val color: Int, val w: Float, val pts: List<Float>, val text: String? = null)

/** Ponto de destino: Intro, S, Coda… */
data class Anchor(val label: String, val page: Int, val x: Float, val y: Float)

/** Sinal clicável que leva a uma âncora (ex.: "To Coda" → Coda). */
data class Jump(val target: String, val page: Int, val x: Float, val y: Float)

data class FileNotes(
    val pages: Map<Int, List<Ann>> = emptyMap(),
    val anchors: List<Anchor> = emptyList(),
    val jumps: List<Jump> = emptyList(),
    /** Recorte manual (só na tela), em frações l,t,r,b. Chave -1 = todas as páginas. */
    val crops: Map<Int, List<Float>> = emptyMap(),
    /** Metrônomo desta música. */
    val bpm: Int? = null,
    val beats: Int? = null,
    /** Ajustes de imagem desta partitura (null = padrão: só endireitar). */
    val look: Look? = null,
) {
    fun isEmpty() = pages.values.all { it.isEmpty() } && anchors.isEmpty() && jumps.isEmpty() &&
        crops.isEmpty() && bpm == null && beats == null && look == null

    fun cropFor(page: Int): android.graphics.RectF? =
        (crops[page] ?: crops[-1])?.takeIf { it.size == 4 }?.let { android.graphics.RectF(it[0], it[1], it[2], it[3]) }

    fun add(page: Int, a: Ann) = copy(pages = pages + (page to (pages[page].orEmpty() + a)))
}

object AnnStore {
    private fun dir(ctx: Context) = File(ctx.filesDir, "anotacoes").apply { mkdirs() }

    private fun key(path: String) =
        MessageDigest.getInstance("MD5").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun fileFor(ctx: Context, path: String) = File(dir(ctx), key(path) + ".json")

    fun load(ctx: Context, path: String): FileNotes = try {
        val f = fileFor(ctx, path)
        if (!f.exists()) FileNotes() else parse(JSONObject(f.readText()))
    } catch (e: Exception) {
        FileNotes()
    }

    fun save(ctx: Context, path: String, n: FileNotes) {
        try {
            val f = fileFor(ctx, path)
            if (n.isEmpty()) {
                f.delete()
                return
            }
            val tmp = File(f.path + ".tmp")
            tmp.writeText(toJson(path, n).toString())
            tmp.renameTo(f)
        } catch (_: Exception) {
        }
    }

    /** Depois de mover/renomear arquivos: as anotações acompanham. */
    fun relink(ctx: Context, moved: Map<String, String>) {
        if (moved.isEmpty()) return
        dir(ctx).listFiles()?.forEach { f ->
            try {
                val o = JSONObject(f.readText())
                val p = o.optString("path")
                val np = moved[p] ?: moved.entries.firstOrNull { p.startsWith(it.key + "/") }
                    ?.let { it.value + p.removePrefix(it.key) }
                if (np != null && np != p) {
                    save(ctx, np, parse(o))
                    f.delete()
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Todos os arquivos de anotação (para o backup). */
    fun allFiles(ctx: Context): List<File> = dir(ctx).listFiles()?.filter { it.name.endsWith(".json") }.orEmpty()

    private fun parse(o: JSONObject): FileNotes {
        val pages = HashMap<Int, List<Ann>>()
        o.optJSONObject("pages")?.let { po ->
            po.keys().forEach { k ->
                val arr = po.getJSONArray(k)
                pages[k.toInt()] = (0 until arr.length()).map { i ->
                    val a = arr.getJSONObject(i)
                    val pts = a.getJSONArray("p")
                    Ann(
                        a.getString("k"), a.getInt("c"), a.getDouble("w").toFloat(),
                        (0 until pts.length()).map { pts.getDouble(it).toFloat() },
                        if (a.has("t")) a.getString("t") else null,
                    )
                }
            }
        }
        val anchors = o.optJSONArray("anchors")?.let { arr ->
            (0 until arr.length()).map { i ->
                val a = arr.getJSONObject(i)
                Anchor(a.getString("l"), a.getInt("pg"), a.getDouble("x").toFloat(), a.getDouble("y").toFloat())
            }
        }.orEmpty()
        val jumps = o.optJSONArray("jumps")?.let { arr ->
            (0 until arr.length()).map { i ->
                val a = arr.getJSONObject(i)
                Jump(a.getString("to"), a.getInt("pg"), a.getDouble("x").toFloat(), a.getDouble("y").toFloat())
            }
        }.orEmpty()
        val crops = HashMap<Int, List<Float>>()
        o.optJSONObject("crops")?.let { co ->
            co.keys().forEach { k ->
                val a = co.getJSONArray(k)
                crops[k.toInt()] = (0 until a.length()).map { a.getDouble(it).toFloat() }
            }
        }
        return FileNotes(
            pages, anchors, jumps, crops,
            if (o.has("bpm")) o.getInt("bpm") else null,
            if (o.has("beats")) o.getInt("beats") else null,
            o.optJSONObject("look")?.let { l ->
                Look(
                    l.optBoolean("d", true), l.optDouble("p", 0.0).toFloat(),
                    l.optDouble("i", 0.0).toFloat(), l.optDouble("s", 0.0).toFloat(),
                )
            },
        )
    }

    private fun toJson(path: String, n: FileNotes): JSONObject {
        val po = JSONObject()
        n.pages.forEach { (pg, list) ->
            if (list.isEmpty()) return@forEach
            val arr = JSONArray()
            list.forEach { a ->
                val o = JSONObject().put("k", a.kind).put("c", a.color).put("w", a.w.toDouble())
                    .put("p", JSONArray(a.pts.map { it.toDouble() }))
                if (a.text != null) o.put("t", a.text)
                arr.put(o)
            }
            po.put(pg.toString(), arr)
        }
        val co = JSONObject()
        n.crops.forEach { (k, v) -> co.put(k.toString(), JSONArray(v.map { it.toDouble() })) }
        val root = JSONObject()
        if (n.bpm != null) root.put("bpm", n.bpm)
        if (n.beats != null) root.put("beats", n.beats)
        n.look?.let { l ->
            root.put(
                "look",
                JSONObject().put("d", l.deskew).put("p", l.paper.toDouble()).put("i", l.ink.toDouble()).put("s", l.sharp.toDouble()),
            )
        }
        return root
            .put("crops", co)
            .put("path", path)
            .put("pages", po)
            .put("anchors", JSONArray(n.anchors.map {
                JSONObject().put("l", it.label).put("pg", it.page).put("x", it.x.toDouble()).put("y", it.y.toDouble())
            }))
            .put("jumps", JSONArray(n.jumps.map {
                JSONObject().put("to", it.target).put("pg", it.page).put("x", it.x.toDouble()).put("y", it.y.toDouble())
            }))
    }
}

/**
 * Conversão entre a página (frações 0..1) e a tela, para uma imagem desenhada com "Fit"
 * numa caixa [box], sendo que a imagem mostra só a parte [shown] da página.
 */
class PageMap(val box: Size, val bmpW: Int, val bmpH: Int, val shown: RectF) {
    private val k = minOf(box.width / bmpW, box.height / bmpH)
    private val ox = (box.width - bmpW * k) / 2f
    private val oy = (box.height - bmpH * k) / 2f
    private val sw = shown.width().coerceAtLeast(1e-4f)
    private val sh = shown.height().coerceAtLeast(1e-4f)

    /** Largura da página inteira, em pixels de tela. */
    val pageW: Float = bmpW * k / sw

    fun toView(x: Float, y: Float) = Offset(
        ox + (x - shown.left) / sw * bmpW * k,
        oy + (y - shown.top) / sh * bmpH * k,
    )

    fun toPage(p: Offset): Offset = Offset(
        shown.left + (p.x - ox) / (bmpW * k) * sw,
        shown.top + (p.y - oy) / (bmpH * k) * sh,
    )
}

object AnnDraw {
    val AnchorColor = Color(0xFF00897B)
    val JumpColor = Color(0xFFEF6C00)

    fun draw(scope: DrawScope, m: PageMap, list: List<Ann>, tm: TextMeasurer) = with(scope) {
        for (a in list) {
            val c = Color(a.color)
            when (a.kind) {
                "pen", "hl" -> {
                    if (a.pts.size < 2) continue
                    val path = Path()
                    val p0 = m.toView(a.pts[0], a.pts[1])
                    path.moveTo(p0.x, p0.y)
                    var i = 2
                    while (i + 1 < a.pts.size) {
                        val p = m.toView(a.pts[i], a.pts[i + 1])
                        path.lineTo(p.x, p.y)
                        i += 2
                    }
                    if (a.pts.size == 2) path.lineTo(p0.x + 0.5f, p0.y)
                    drawPath(
                        path,
                        if (a.kind == "hl") c.copy(alpha = 0.35f) else c,
                        style = Stroke(
                            width = (a.w * m.pageW).coerceAtLeast(1.5f),
                            cap = if (a.kind == "hl") StrokeCap.Square else StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                }
                "circle" -> {
                    if (a.pts.size < 4) continue
                    val tl = m.toView(minOf(a.pts[0], a.pts[2]), minOf(a.pts[1], a.pts[3]))
                    val br = m.toView(maxOf(a.pts[0], a.pts[2]), maxOf(a.pts[1], a.pts[3]))
                    drawOval(c, tl, Size(br.x - tl.x, br.y - tl.y), style = Stroke(width = (a.w * m.pageW).coerceAtLeast(1.5f)))
                }
                "text", "finger" -> {
                    if (a.pts.size < 2 || a.text.isNullOrEmpty()) continue
                    val p = m.toView(a.pts[0], a.pts[1])
                    val px = (a.w * m.pageW).coerceAtLeast(8f)
                    val layout = tm.measure(
                        a.text,
                        TextStyle(
                            color = c,
                            fontSize = px.toSp(),
                            fontWeight = if (a.kind == "finger") FontWeight.Bold else FontWeight.Medium,
                        ),
                    )
                    // O ponto tocado fica no centro do texto.
                    drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
                }
            }
        }
    }

    /** Etiqueta (âncora ou salto) desenhada na página. Devolve o retângulo na tela, para o toque. */
    fun tag(scope: DrawScope, m: PageMap, x: Float, y: Float, label: String, color: Color, tm: TextMeasurer) = with(scope) {
        val p = m.toView(x, y)
        val px = (0.022f * m.pageW).coerceIn(11f, 40f)
        val layout = tm.measure(label, TextStyle(color = Color.White, fontSize = px.toSp(), fontWeight = FontWeight.Bold))
        val pad = px * 0.35f
        val w = layout.size.width + pad * 2
        val h = layout.size.height + pad
        val tl = Offset(p.x - w / 2f, p.y - h / 2f)
        drawRoundRect(color.copy(alpha = 0.85f), tl, Size(w, h), androidx.compose.ui.geometry.CornerRadius(h / 2f))
        drawText(layout, topLeft = Offset(tl.x + pad, tl.y + pad / 2f))
    }

    /** Distância (em pixels de tela) de um toque até uma anotação, para a borracha. */
    fun distance(m: PageMap, a: Ann, at: Offset): Float = when (a.kind) {
        "pen", "hl" -> {
            // Distância até cada trecho do traço (não só aos pontos).
            var best = Float.MAX_VALUE
            var prev = m.toView(a.pts[0], a.pts[1])
            best = minOf(best, hypot(prev.x - at.x, prev.y - at.y))
            var i = 2
            while (i + 1 < a.pts.size) {
                val p = m.toView(a.pts[i], a.pts[i + 1])
                best = minOf(best, segDist(at, prev, p))
                prev = p
                i += 2
            }
            (best - a.w * m.pageW / 2f).coerceAtLeast(0f)
        }
        "circle" -> {
            val c = m.toView((a.pts[0] + a.pts[2]) / 2f, (a.pts[1] + a.pts[3]) / 2f)
            val r = m.toView(a.pts[2], a.pts[3])
            val rad = hypot(r.x - c.x, r.y - c.y) / 1.4f
            kotlin.math.abs(hypot(at.x - c.x, at.y - c.y) - rad)
        }
        else -> {
            // Texto/dedilhado: conta o tamanho da letra.
            val p = m.toView(a.pts[0], a.pts[1])
            val half = a.w * m.pageW * maxOf(1, a.text?.length ?: 1) * 0.35f
            (hypot(p.x - at.x, p.y - at.y) - half).coerceAtLeast(0f)
        }
    }

    private fun segDist(p: Offset, a: Offset, b: Offset): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 < 1e-6f) return hypot(p.x - a.x, p.y - a.y)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }
}

/** Tamanhos (fração da largura da página) para texto e dedilhado: P, M, G, GG. */
val TextSizes = listOf(0.018f, 0.026f, 0.034f, 0.046f)
val FingerSizes = listOf(0.008f, 0.012f, 0.016f, 0.022f)

/** Rótulos sugeridos para âncoras. */
val AnchorPresets = listOf("Intro", "A", "B", "Refrão", "Ponte", "Solo", "S", "S2", "Coda", "Coda 2", "Fine", "Final")
