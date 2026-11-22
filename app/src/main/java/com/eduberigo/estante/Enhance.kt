package com.eduberigo.estante

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * "Modo legível" para partes mal digitalizadas (só na tela; o arquivo não muda):
 * 1) endireita página torta (acha o ângulo pelas linhas da pauta);
 * 2) limpa o fundo cinza e escurece o traço (níveis automáticos);
 * 3) recorta as margens, se o recorte automático estiver ligado.
 * Curvatura (página escaneada com o livro aberto) não é corrigida.
 */
object Enhance {
    fun process(src: Bitmap, crop: Boolean): Bitmap = processR(src, crop).first

    /** Igual a [process], mas também devolve a área da página mostrada (frações). */
    fun processR(src: Bitmap, crop: Boolean): Pair<Bitmap, android.graphics.RectF> {
        var shown = android.graphics.RectF(0f, 0f, 1f, 1f)
        var b = src
        val a = skewAngle(b)
        if (abs(a) >= 0.15f) b = rotate(b, a)
        b = levels(b)
        if (crop) {
            Crop.detect(b)?.let { r ->
                val x = (r.left * b.width).toInt()
                val y = (r.top * b.height).toInt()
                val w = (r.width() * b.width).toInt().coerceIn(1, b.width - x)
                val h = (r.height() * b.height).toInt().coerceIn(1, b.height - y)
                shown = android.graphics.RectF(
                    x.toFloat() / b.width, y.toFloat() / b.height,
                    (x + w).toFloat() / b.width, (y + h).toFloat() / b.height,
                )
                b = Bitmap.createBitmap(b, x, y, w, h)
            }
        }
        return b to shown
    }

    private fun lum(c: Int): Int =
        (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000

    /** Ângulo (graus) que deixa as linhas horizontais. Procura entre -6° e +6°. */
    fun skewAngle(src: Bitmap): Float {
        val w = 500.coerceAtMost(src.width)
        val h = (src.height.toLong() * w / src.width).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== src) small.recycle()
        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        for (y in 0 until h) for (x in 0 until w) {
            if (lum(px[y * w + x]) < 128) {
                xs.add(x)
                ys.add(y)
            }
        }
        if (xs.size < 200) return 0f
        val pad = (w * tan(Math.toRadians(7.0))).toInt() + 2
        var best = 0f
        var bestScore = -1.0
        var a = -6f
        while (a <= 6.001f) {
            val t = tan(Math.toRadians(a.toDouble()))
            val rows = IntArray(h + 2 * pad)
            for (i in xs.indices) {
                val r = (ys[i] + xs[i] * t).roundToInt() + pad
                if (r in rows.indices) rows[r]++
            }
            var score = 0.0
            for (v in rows) score += v.toDouble() * v
            if (score > bestScore) {
                bestScore = score
                best = a
            }
            a += 0.2f
        }
        return best
    }

    fun rotate(src: Bitmap, degrees: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        c.rotate(degrees, src.width / 2f, src.height / 2f)
        c.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** Níveis automáticos em tons de cinza: fundo vira branco, traço fica preto e firme. */
    fun levels(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val hist = IntArray(256)
        for (c in px) hist[lum(c)]++
        fun pct(p: Double): Int {
            val target = (px.size * p).toLong()
            var acc = 0L
            for (v in 0..255) {
                acc += hist[v]
                if (acc >= target) return v
            }
            return 255
        }
        val lo = pct(0.005)
        // A maior parte de uma partitura é fundo: o percentil 35 (a partir do escuro) já é fundo.
        val hi = (pct(0.35) - 12).coerceAtLeast(lo + 40).coerceAtMost(255)
        // Página quase toda branca (ou sem contraste): não mexe.
        if (hi - lo < 20) return src
        val lut = IntArray(256) { v ->
            val f = ((v - lo).toDouble() / (hi - lo)).coerceIn(0.0, 1.0)
            (255 * f.pow(1.6)).roundToInt().coerceIn(0, 255)
        }
        for (i in px.indices) {
            val g = lut[lum(px[i])]
            px[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }
}
