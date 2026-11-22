package com.eduberigo.estante

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Grava no próprio arquivo (para abrir em qualquer leitor de PDF):
 * - anotações (vetoriais; saltos "→ Coda" viram links clicáveis);
 * - recortes (só muda a área visível; sem perder qualidade);
 * - ajustes de imagem (nesse caso a página vira imagem).
 * Sempre escreve num arquivo novo [out]; o original não muda.
 */
object Flatten {
    data class Opts(val notes: Boolean, val crops: Boolean, val look: Boolean, val autoCrop: Boolean)

    private fun hasNotes(n: FileNotes) = n.pages.values.any { it.isNotEmpty() } || n.anchors.isNotEmpty() || n.jumps.isNotEmpty()
    private fun hasTone(n: FileNotes) = n.look?.let { it.tone || it.sharp != 0f } == true

    /** Só caracteres que a fonte padrão do PDF (WinAnsi) aceita. */
    private fun safeText(t: String): String = t.map { c ->
        when {
            c == '→' -> '>'
            c.code in 32..126 || c.code in 160..255 -> c
            else -> '?'
        }
    }.joinToString("")

    suspend fun process(ctx: Context, src: File, out: File, o: Opts) = withContext(Dispatchers.IO) {
        val nt = AnnStore.load(ctx, src.absolutePath)
        when (Fs.kindOf(src)) {
            Kind.PDF -> {
                val needNotes = o.notes && hasNotes(nt)
                val needCrop = o.crops && (nt.crops.isNotEmpty() || o.autoCrop)
                val needLook = o.look && hasTone(nt)
                when {
                    needLook -> raster(PdfDoc(src), nt, o, out)
                    !needNotes && !needCrop -> src.copyTo(out, overwrite = true)
                    else -> vector(src, nt, o, out, needNotes, needCrop)
                }
            }
            Kind.IMAGE -> raster(ImageDoc(src), nt, o, out)
            else -> throw IllegalArgumentException("tipo não suportado")
        }
    }

    // ---------- PDF vetorial ----------

    private suspend fun vector(src: File, nt: FileNotes, o: Opts, out: File, notes: Boolean, crops: Boolean) {
        // Margens automáticas são calculadas antes (com o leitor de PDF do Android).
        val auto = HashMap<Int, RectF?>()
        if (crops && o.autoCrop) {
            val pd = PdfDoc(src)
            try {
                for (i in 0 until pd.pageCount) if (nt.cropFor(i) == null) auto[i] = pd.autoCropRect(i)
            } finally {
                pd.close()
            }
        }
        PDDocument.load(src, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            if (doc.isEncrypted) doc.setAllSecurityToBeRemoved(true)
            if (notes && (0 until doc.numberOfPages).any { ((doc.getPage(it).rotation % 360) + 360) % 360 != 0 }) {
                // Página girada: mais seguro gravar como imagem.
                doc.close()
                raster(PdfDoc(src), nt, o, out)
                return
            }
            if (notes) {
                for (i in 0 until doc.numberOfPages) {
                    val page = doc.getPage(i)
                    val box = page.cropBox
                    fun px(x: Float) = box.lowerLeftX + x * box.width
                    fun py(y: Float) = box.upperRightY - y * box.height
                    val anns = nt.pages[i].orEmpty()
                    val anchors = nt.anchors.filter { it.page == i }
                    val jumps = nt.jumps.filter { it.page == i }
                    if (anns.isEmpty() && anchors.isEmpty() && jumps.isEmpty()) continue
                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        for (a in anns) drawVector(cs, a, box.width, ::px, ::py)
                        anchors.forEach { tag(cs, it.x, it.y, it.label, 0xFF00897B.toInt(), box.width, ::px, ::py) }
                        jumps.forEach { j ->
                            val r = tag(cs, j.x, j.y, "> " + j.target, 0xFFEF6C00.toInt(), box.width, ::px, ::py)
                            val dest = nt.anchors.firstOrNull { it.label.equals(j.target, true) }
                            if (dest != null && dest.page in 0 until doc.numberOfPages) {
                                val link = PDAnnotationLink()
                                link.rectangle = r
                                link.borderStyle = PDBorderStyleDictionary().apply { width = 0f }
                                val d = PDPageFitDestination()
                                d.page = doc.getPage(dest.page)
                                link.action = PDActionGoTo().apply { destination = d }
                                val list = page.annotations
                                list.add(link)
                                page.annotations = list
                            }
                        }
                    }
                }
            }
            if (crops) {
                for (i in 0 until doc.numberOfPages) {
                    val r = nt.cropFor(i) ?: auto[i] ?: continue
                    val page = doc.getPage(i)
                    val b = page.cropBox
                    val llx = b.lowerLeftX + r.left * b.width
                    val urx = b.lowerLeftX + r.right * b.width
                    val ury = b.upperRightY - r.top * b.height
                    val lly = b.upperRightY - r.bottom * b.height
                    page.cropBox = PDRectangle(llx, lly, urx - llx, ury - lly)
                }
            }
            doc.save(out)
        }
    }

    private fun rgb(c: Int) = floatArrayOf(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f)

    private fun drawVector(cs: PDPageContentStream, a: Ann, pageW: Float, px: (Float) -> Float, py: (Float) -> Float) {
        val (r, g, b) = rgb(a.color).let { Triple(it[0], it[1], it[2]) }
        when (a.kind) {
            "pen", "hl" -> {
                if (a.pts.size < 2) return
                cs.saveGraphicsState()
                if (a.kind == "hl") {
                    val gs = PDExtendedGraphicsState()
                    gs.strokingAlphaConstant = 0.35f
                    cs.setGraphicsStateParameters(gs)
                }
                cs.setStrokingColor(r, g, b)
                cs.setLineWidth((a.w * pageW).coerceAtLeast(0.5f))
                cs.setLineCapStyle(if (a.kind == "hl") 2 else 1)
                cs.setLineJoinStyle(1)
                cs.moveTo(px(a.pts[0]), py(a.pts[1]))
                var i = 2
                while (i + 1 < a.pts.size) {
                    cs.lineTo(px(a.pts[i]), py(a.pts[i + 1]))
                    i += 2
                }
                if (a.pts.size == 2) cs.lineTo(px(a.pts[0]) + 0.3f, py(a.pts[1]))
                cs.stroke()
                cs.restoreGraphicsState()
            }
            "circle" -> {
                if (a.pts.size < 4) return
                val x0 = px(minOf(a.pts[0], a.pts[2]))
                val x1 = px(maxOf(a.pts[0], a.pts[2]))
                val y0 = py(maxOf(a.pts[1], a.pts[3]))
                val y1 = py(minOf(a.pts[1], a.pts[3]))
                val cx = (x0 + x1) / 2f
                val cy = (y0 + y1) / 2f
                val rx = (x1 - x0) / 2f
                val ry = (y1 - y0) / 2f
                val k = 0.5523f
                cs.saveGraphicsState()
                cs.setStrokingColor(r, g, b)
                cs.setLineWidth((a.w * pageW).coerceAtLeast(0.5f))
                cs.moveTo(cx + rx, cy)
                cs.curveTo(cx + rx, cy + k * ry, cx + k * rx, cy + ry, cx, cy + ry)
                cs.curveTo(cx - k * rx, cy + ry, cx - rx, cy + k * ry, cx - rx, cy)
                cs.curveTo(cx - rx, cy - k * ry, cx - k * rx, cy - ry, cx, cy - ry)
                cs.curveTo(cx + k * rx, cy - ry, cx + rx, cy - k * ry, cx + rx, cy)
                cs.stroke()
                cs.restoreGraphicsState()
            }
            "text", "finger" -> {
                val t = safeText(a.text ?: return)
                if (t.isEmpty() || a.pts.size < 2) return
                val font = if (a.kind == "finger") PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA
                val size = (a.w * pageW).coerceAtLeast(4f)
                val tw = font.getStringWidth(t) / 1000f * size
                cs.beginText()
                cs.setNonStrokingColor(r, g, b)
                cs.setFont(font, size)
                cs.newLineAtOffset(px(a.pts[0]) - tw / 2f, py(a.pts[1]) - size * 0.35f)
                cs.showText(t)
                cs.endText()
            }
        }
    }

    /** Etiqueta (âncora ou salto). Devolve o retângulo, para o link. */
    private fun tag(
        cs: PDPageContentStream, x: Float, y: Float, label: String, color: Int, pageW: Float,
        px: (Float) -> Float, py: (Float) -> Float,
    ): PDRectangle {
        val t = safeText(label)
        val font = PDType1Font.HELVETICA_BOLD
        val size = (0.022f * pageW).coerceIn(6f, 20f)
        val tw = font.getStringWidth(t) / 1000f * size
        val pad = size * 0.35f
        val w = tw + pad * 2
        val h = size + pad
        val l = px(x) - w / 2f
        val bt = py(y) - h / 2f
        val (r, g, b) = rgb(color).let { Triple(it[0], it[1], it[2]) }
        cs.saveGraphicsState()
        val gs = PDExtendedGraphicsState()
        gs.nonStrokingAlphaConstant = 0.85f
        cs.setGraphicsStateParameters(gs)
        cs.setNonStrokingColor(r, g, b)
        cs.addRect(l, bt, w, h)
        cs.fill()
        cs.restoreGraphicsState()
        cs.beginText()
        cs.setNonStrokingColor(1f, 1f, 1f)
        cs.setFont(font, size)
        cs.newLineAtOffset(l + pad, bt + pad * 0.9f)
        cs.showText(t)
        cs.endText()
        return PDRectangle(l, bt, w, h)
    }

    // ---------- como imagem (ajustes de imagem, ou arquivo de imagem) ----------

    private suspend fun raster(d: Doc, nt: FileNotes, o: Opts, out: File) {
        try {
            PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                for (i in 0 until d.pageCount) {
                    val src = d.render(
                        i, 1600, 2260,
                        crop = o.crops && o.autoCrop,
                        look = if (o.look) nt.look else null,
                        manual = if (o.crops) nt.cropFor(i) else null,
                    ) ?: throw IllegalStateException("página ${i + 1} não renderizou")
                    val bmp = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true)
                    if (o.notes) drawRaster(bmp, d.shownRect(i), nt.pages[i].orEmpty(), nt.anchors.filter { it.page == i }, nt.jumps.filter { it.page == i })
                    val w = 595f
                    val h = w * bmp.height / bmp.width
                    val page = PDPage(PDRectangle(w, h))
                    doc.addPage(page)
                    val x = JPEGFactory.createFromImage(doc, bmp, 0.92f)
                    PDPageContentStream(doc, page).use { cs -> cs.drawImage(x, 0f, 0f, w, h) }
                    bmp.recycle()
                }
                doc.save(out)
            }
        } finally {
            d.close()
        }
    }

    private fun drawRaster(bmp: Bitmap, shown: RectF, anns: List<Ann>, anchors: List<Anchor>, jumps: List<Jump>) {
        val c = Canvas(bmp)
        val sw = shown.width().coerceAtLeast(1e-4f)
        val sh = shown.height().coerceAtLeast(1e-4f)
        val pageW = bmp.width / sw
        fun x(v: Float) = (v - shown.left) / sw * bmp.width
        fun y(v: Float) = (v - shown.top) / sh * bmp.height
        for (a in anns) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = a.color }
            when (a.kind) {
                "pen", "hl" -> {
                    if (a.pts.size < 2) continue
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = (a.w * pageW).coerceAtLeast(1.5f)
                    p.strokeCap = if (a.kind == "hl") Paint.Cap.SQUARE else Paint.Cap.ROUND
                    p.strokeJoin = Paint.Join.ROUND
                    if (a.kind == "hl") p.alpha = 90
                    val path = android.graphics.Path()
                    path.moveTo(x(a.pts[0]), y(a.pts[1]))
                    var i = 2
                    while (i + 1 < a.pts.size) {
                        path.lineTo(x(a.pts[i]), y(a.pts[i + 1]))
                        i += 2
                    }
                    if (a.pts.size == 2) path.lineTo(x(a.pts[0]) + 0.5f, y(a.pts[1]))
                    c.drawPath(path, p)
                }
                "circle" -> {
                    if (a.pts.size < 4) continue
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = (a.w * pageW).coerceAtLeast(1.5f)
                    c.drawOval(
                        RectF(
                            x(minOf(a.pts[0], a.pts[2])), y(minOf(a.pts[1], a.pts[3])),
                            x(maxOf(a.pts[0], a.pts[2])), y(maxOf(a.pts[1], a.pts[3])),
                        ),
                        p,
                    )
                }
                "text", "finger" -> {
                    val t = a.text ?: continue
                    p.textSize = (a.w * pageW).coerceAtLeast(8f)
                    p.isFakeBoldText = a.kind == "finger"
                    p.textAlign = Paint.Align.CENTER
                    c.drawText(t, x(a.pts[0]), y(a.pts[1]) + p.textSize * 0.35f, p)
                }
            }
        }
        fun tag(xx: Float, yy: Float, label: String, color: Int) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = (0.022f * pageW).coerceIn(11f, 40f)
                isFakeBoldText = true
            }
            val tw = p.measureText(label)
            val pad = p.textSize * 0.35f
            val w = tw + pad * 2
            val h = p.textSize + pad
            val l = x(xx) - w / 2f
            val t = y(yy) - h / 2f
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; alpha = 215 }
            c.drawRoundRect(RectF(l, t, l + w, t + h), h / 2f, h / 2f, bg)
            p.color = android.graphics.Color.WHITE
            c.drawText(label, l + pad, t + h - pad * 1.1f, p)
        }
        anchors.forEach { tag(it.x, it.y, it.label, 0xFF00897B.toInt()) }
        jumps.forEach { tag(it.x, it.y, "→ " + it.target, 0xFFEF6C00.toInt()) }
    }
}
