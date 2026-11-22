package com.eduberigo.estante

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * Ajustes de visualização de uma partitura (salvos por arquivo):
 * - deskew: endireitar página torta (ligado por padrão);
 * - paper: 0..1, deixa o fundo cinza branco;
 * - ink: -1..1, preto mais claro/menos saturado (negativo) ou mais escuro e firme (positivo);
 * - sharp: -1..1, mais suave (negativo) ou mais nítido (positivo).
 */
data class Look(
    val deskew: Boolean = true,
    val paper: Float = 0f,
    val ink: Float = 0f,
    val sharp: Float = 0f,
) {
    val tone: Boolean get() = paper != 0f || ink != 0f
    val isDefault: Boolean get() = this == Look()

    companion object {
        /** Atalho "Legível": fundo branco, preto firme, um pouco mais nítido. */
        val LEGIVEL = Look(true, 0.7f, 0.35f, 0.25f)
    }
}

object Enhance {
    private fun lum(c: Int): Int =
        (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000

    /** Aplica os ajustes. [angle] = ângulo já calculado (cache), ou null para calcular. */
    fun apply(src: Bitmap, look: Look, angle: Float? = null): Bitmap {
        var b = src
        if (look.deskew) {
            val a = angle ?: skewAngle(b)
            if (abs(a) >= 0.15f) b = rotate(b, a)
        }
        if (look.tone || look.sharp != 0f) b = tone(b, look)
        return b
    }

    /** Ângulo (graus) que deixa as linhas da pauta horizontais. Procura entre -6° e +6°. */
    fun skewAngle(src: Bitmap): Float {
        val w = 500.coerceAtMost(src.width)
        val h = (src.height.toLong() * w / src.width).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== src) small.recycle()
        var n = 0
        val xs = IntArray(px.size)
        val ys = IntArray(px.size)
        for (y in 0 until h) for (x in 0 until w) {
            if (lum(px[y * w + x]) < 128) {
                xs[n] = x
                ys[n] = y
                n++
            }
        }
        if (n < 200) return 0f
        val pad = (w * tan(Math.toRadians(7.0))).toInt() + 2
        var best = 0f
        var bestScore = -1.0
        var a = -6f
        val rows = IntArray(h + 2 * pad)
        while (a <= 6.001f) {
            val t = tan(Math.toRadians(a.toDouble()))
            rows.fill(0)
            for (i in 0 until n) {
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

    /** Gira sem cortar os cantos (a imagem cresce o necessário, fundo branco). */
    fun rotate(src: Bitmap, degrees: Float): Bitmap {
        val rad = Math.toRadians(degrees.toDouble())
        val c = abs(cos(rad))
        val s = abs(sin(rad))
        val w = (src.width * c + src.height * s).roundToInt()
        val h = (src.width * s + src.height * c).roundToInt()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        cv.drawColor(Color.WHITE)
        cv.translate(w / 2f, h / 2f)
        cv.rotate(degrees)
        cv.translate(-src.width / 2f, -src.height / 2f)
        cv.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** Fundo, preto e nitidez, em tons de cinza. */
    fun tone(src: Bitmap, look: Look): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val g = IntArray(px.size) { lum(px[it]) }

        if (look.tone) {
            val hist = IntArray(256)
            for (v in g) hist[v]++
            fun pct(p: Double): Int {
                val target = (g.size * p).toLong()
                var acc = 0L
                for (v in 0..255) {
                    acc += hist[v]
                    if (acc >= target) return v
                }
                return 255
            }
            val p = look.paper.coerceIn(0f, 1f)
            val k = look.ink.coerceIn(-1f, 1f)
            // Fundo: com paper = 1, tudo a partir do tom típico do papel vira branco.
            val bg = (pct(0.35) - 12).coerceIn(40, 255)
            val hi = (255 - (255 - bg) * p).roundToInt().coerceIn(40, 255)
            val lo = (pct(0.005) * p).roundToInt().coerceIn(0, hi - 20)
            // Preto: k > 0 escurece os meios-tons; k < 0 clareia e tira a saturação do preto.
            val gamma = if (k >= 0) 1.0 + 0.9 * k else 1.0 / (1.0 + 0.6 * -k)
            val floor = if (k < 0) (-k * 110).toDouble() else 0.0
            val lut = IntArray(256) { v ->
                val f = ((v - lo).toDouble() / (hi - lo)).coerceIn(0.0, 1.0)
                (floor + (255 - floor) * f.pow(gamma)).roundToInt().coerceIn(0, 255)
            }
            for (i in g.indices) g[i] = lut[g[i]]
        }

        val sh = look.sharp.coerceIn(-1f, 1f)
        val out = if (sh != 0f && w > 2 && h > 2) {
            // Desfoque 3x3. Nitidez = original + s·(original − desfoque); suave = mistura com o desfoque.
            val res = IntArray(g.size)
            val amt = if (sh > 0) sh * 1.6f else sh
            for (y in 0 until h) {
                val y0 = if (y > 0) y - 1 else y
                val y1 = if (y < h - 1) y + 1 else y
                for (x in 0 until w) {
                    val x0 = if (x > 0) x - 1 else x
                    val x1 = if (x < w - 1) x + 1 else x
                    val blur = (g[y0 * w + x0] + g[y0 * w + x] + g[y0 * w + x1] +
                        g[y * w + x0] + g[y * w + x] + g[y * w + x1] +
                        g[y1 * w + x0] + g[y1 * w + x] + g[y1 * w + x1]) / 9f
                    val v = g[y * w + x]
                    res[y * w + x] = (v + amt * (v - blur)).roundToInt().coerceIn(0, 255)
                }
            }
            res
        } else {
            g
        }
        for (i in px.indices) {
            val v = out[i]
            px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }
}
