package com.eduberigo.estante

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Escopo para tarefas que não podem ser canceladas junto com a tela (ex.: fechar um PDF). */
val AppScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Algo que tem páginas para desenhar: um PDF ou uma imagem (1 página). */
interface Doc {
    val pageCount: Int
    /** Para imagens: cada página é um arquivo da mesma pasta (páginas de um songbook). */
    val pageFiles: List<File>? get() = null
    /** Página em que abrir (a imagem tocada, dentro da sequência da pasta). */
    val startPage: Int get() = 0
    /** crop = recorte automático das margens brancas (só na tela; o arquivo não muda). */
    suspend fun render(index: Int, maxW: Int, maxH: Int, crop: Boolean = false): Bitmap?
    fun close()
}

fun openDoc(f: File): Doc? = try {
    when (Fs.kindOf(f)) {
        Kind.PDF -> PdfDoc(f)
        Kind.IMAGE -> {
            val sib = f.parentFile?.listFiles()
                ?.filter { it.isFile && !it.name.startsWith(".") && Fs.kindOf(it, false) == Kind.IMAGE }
                ?.sortedWith(Comparator { a, b -> NaturalOrder.compare(a.name, b.name) })
                .orEmpty()
            if (sib.size > 1 && f in sib) MultiImageDoc(sib, sib.indexOf(f)) else ImageDoc(f)
        }
        else -> null
    }
} catch (e: Throwable) {
    null
}

class PdfDoc(file: File) : Doc {
    private val pfd: ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = try {
        PdfRenderer(pfd)
    } catch (e: Throwable) {
        pfd.close()
        throw e
    }
    private val mutex = Mutex()
    private var closed = false

    override val pageCount: Int = renderer.pageCount

    private val cropCache = HashMap<Int, RectF?>()

    private fun detectCrop(page: PdfRenderer.Page): RectF? {
        val w = 360
        val h = (w.toFloat() * page.height / page.width).toInt().coerceIn(1, w * 4)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        val r = Crop.detect(bmp)
        bmp.recycle()
        return r
    }

    override suspend fun render(index: Int, maxW: Int, maxH: Int, crop: Boolean): Bitmap? = mutex.withLock {
        if (closed || index !in 0 until pageCount || maxW <= 0 || maxH <= 0) return@withLock null
        withContext(Dispatchers.IO) {
            try {
                val page = renderer.openPage(index)
                try {
                    val pw = page.width.toFloat()
                    val ph = page.height.toFloat()
                    val r = if (crop) {
                        if (cropCache.containsKey(index)) cropCache[index] else detectCrop(page).also { cropCache[index] = it }
                    } else {
                        null
                    }
                    val cx = (r?.left ?: 0f) * pw
                    val cy = (r?.top ?: 0f) * ph
                    val cw = ((r?.width() ?: 1f) * pw).coerceAtLeast(1f)
                    val ch = ((r?.height() ?: 1f) * ph).coerceAtLeast(1f)
                    val s = minOf(maxW / cw, maxH / ch)
                    val w = (cw * s).toInt().coerceAtLeast(1)
                    val h = (ch * s).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    val m = Matrix()
                    m.postScale(s, s)
                    m.postTranslate(-cx * s, -cy * s)
                    page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                } finally {
                    page.close()
                }
            } catch (e: Throwable) {
                null
            }
        }
    }

    override fun close() {
        AppScope.launch {
            mutex.withLock {
                if (!closed) {
                    closed = true
                    try { renderer.close() } catch (_: Throwable) {}
                    try { pfd.close() } catch (_: Throwable) {}
                }
            }
        }
    }
}

class ImageDoc(private val file: File) : Doc {
    override val pageCount: Int = 1
    override suspend fun render(index: Int, maxW: Int, maxH: Int, crop: Boolean): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                if (!crop) return@withContext decodeImage(file, maxW, maxH)
                val big = decodeImage(file, maxW * 2, maxH * 2)
                val r = Crop.detect(big) ?: return@withContext big
                val x = (r.left * big.width).toInt()
                val y = (r.top * big.height).toInt()
                val w = (r.width() * big.width).toInt().coerceIn(1, big.width - x)
                val h = (r.height() * big.height).toInt().coerceIn(1, big.height - y)
                Bitmap.createBitmap(big, x, y, w, h)
            } catch (e: Throwable) {
                null
            }
        }
    override fun close() {}
}

/** As imagens de uma pasta, em ordem, como se fossem páginas de um mesmo documento. */
class MultiImageDoc(private val files: List<File>, override val startPage: Int) : Doc {
    override val pageCount: Int = files.size
    override val pageFiles: List<File> = files
    override suspend fun render(index: Int, maxW: Int, maxH: Int, crop: Boolean): Bitmap? =
        if (index !in files.indices) null else ImageDoc(files[index]).render(0, maxW, maxH, crop)
    override fun close() {}
}

/** Decodifica uma imagem já reduzida e girada conforme a foto (EXIF). */
fun decodeImage(f: File, maxW: Int, maxH: Int): Bitmap {
    val src = ImageDecoder.createSource(f)
    return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
        val sw = info.size.width
        val sh = info.size.height
        val s = minOf(1f, maxW.toFloat() / sw, maxH.toFloat() / sh)
        if (s < 1f) {
            decoder.setTargetSize((sw * s).toInt().coerceAtLeast(1), (sh * s).toInt().coerceAtLeast(1))
        }
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
}

/** Miniaturas: memória + disco (pasta de cache do app). Nunca toca nos arquivos originais. */
object Thumbs {
    private val mem = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val sem = Semaphore(3)

    private fun key(e: Entry, w: Int) = "${e.path}|${e.modified}|${e.size}|$w"

    fun cached(e: Entry, w: Int): Bitmap? = mem.get(key(e, w))

    suspend fun load(ctx: Context, e: Entry, w: Int): Bitmap? {
        val k = key(e, w)
        mem.get(k)?.let { return it }
        return sem.withPermit {
            withContext(Dispatchers.IO) {
                mem.get(k) ?: produce(ctx, e, w, k)?.also { mem.put(k, it) }
            }
        }
    }

    private fun produce(ctx: Context, e: Entry, w: Int, k: String): Bitmap? {
        val disk = File(ctx.cacheDir, "thumbs/${md5(k)}.jpg")
        if (disk.exists()) {
            BitmapFactory.decodeFile(disk.path)?.let { return it }
        }
        val made: Bitmap? = try {
            when (e.kind) {
                Kind.PDF -> pdfFirstPage(e.file, w)
                Kind.IMAGE -> decodeImage(e.file, w, w * 3)
                else -> null
            }
        } catch (t: Throwable) {
            null
        }
        if (made != null) {
            try {
                disk.parentFile?.mkdirs()
                disk.outputStream().use { made.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            } catch (_: Exception) {
            }
        }
        return made
    }

    private fun pdfFirstPage(f: File, w: Int): Bitmap? {
        val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            val r = PdfRenderer(pfd)
            try {
                if (r.pageCount == 0) return null
                val page = r.openPage(0)
                try {
                    val h = (w.toFloat() * page.height / page.width).toInt().coerceIn(1, w * 3)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bmp
                } finally {
                    page.close()
                }
            } finally {
                r.close()
            }
        } finally {
            pfd.close()
        }
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Contagem de páginas para a pré-visualização (rápido, só abre o cabeçalho do PDF). */
fun pdfPageCount(f: File): Int? = try {
    val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    try {
        val r = PdfRenderer(pfd)
        try { r.pageCount } finally { r.close() }
    } finally {
        pfd.close()
    }
} catch (e: Throwable) {
    null
}

/**
 * Acha a área com conteúdo (notas, pautas, texto) e devolve as frações da página a mostrar.
 * Pontinhos isolados (poeira de digitalização) não contam. null = nada a recortar.
 */
object Crop {
    fun detect(b: Bitmap): RectF? {
        val w = b.width
        val h = b.height
        if (w < 8 || h < 8) return null
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        val rows = IntArray(h)
        val cols = IntArray(w)
        for (y in 0 until h) {
            val o = y * w
            for (x in 0 until w) {
                val c = px[o + x]
                val lum = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
                if (lum < 170) {
                    rows[y]++
                    cols[x]++
                }
            }
        }
        val minRow = maxOf(2, w / 150)
        val minCol = maxOf(2, h / 150)
        val top = rows.indexOfFirst { it >= minRow }
        val bottom = rows.indexOfLast { it >= minRow }
        val left = cols.indexOfFirst { it >= minCol }
        val right = cols.indexOfLast { it >= minCol }
        if (top < 0 || left < 0 || bottom <= top || right <= left) return null
        val mx = 0.015f
        val my = 0.012f
        val r = RectF(
            (left.toFloat() / w - mx).coerceAtLeast(0f),
            (top.toFloat() / h - my).coerceAtLeast(0f),
            ((right + 1).toFloat() / w + mx).coerceAtMost(1f),
            ((bottom + 1).toFloat() / h + my).coerceAtMost(1f),
        )
        if (r.width() * r.height() > 0.96f) return null
        if (r.width() < 0.15f || r.height() < 0.05f) return null
        return r
    }
}
