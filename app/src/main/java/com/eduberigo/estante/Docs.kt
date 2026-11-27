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
    /** Que parte da página (frações 0..1) o último render dessa página mostrou. Usado pelas anotações. */
    fun shownRect(index: Int): RectF = RectF(0f, 0f, 1f, 1f)
    /** crop = recorte automático das margens brancas (só na tela; o arquivo não muda). */
    suspend fun render(
        index: Int, maxW: Int, maxH: Int, crop: Boolean = false,
        /** Ajustes de imagem (endireitar, fundo, preto, nitidez); null = como está no arquivo. */
        look: Look? = null,
        /** Recorte manual (frações da página); tem prioridade sobre o automático. */
        manual: RectF? = null,
    ): Bitmap?
    fun close()
}

fun openDoc(f: File): Doc? = try {
    when (Fs.kindOf(f)) {
        Kind.PDF -> PdfDoc(f)
        Kind.IMAGE -> {
            val sib = f.parentFile?.listFiles()
                ?.filter { it.isFile && !it.name.startsWith(".") && Fs.kindOf(it, false) == Kind.IMAGE && (it == f || !Excluded.has(it.absolutePath)) }
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
    private val shown = java.util.concurrent.ConcurrentHashMap<Int, RectF>()
    override fun shownRect(index: Int): RectF = shown[index] ?: RectF(0f, 0f, 1f, 1f)

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

    private val angles = java.util.concurrent.ConcurrentHashMap<Int, Float>()

    /** Margens automáticas desta página (frações), ou null se não há o que cortar. */
    suspend fun autoCropRect(index: Int): RectF? = mutex.withLock {
        if (closed || index !in 0 until pageCount) return@withLock null
        if (cropCache.containsKey(index)) return@withLock cropCache[index]
        withContext(Dispatchers.IO) {
            val page = renderer.openPage(index)
            try { detectCrop(page).also { cropCache[index] = it } } finally { page.close() }
        }
    }

    override suspend fun render(index: Int, maxW: Int, maxH: Int, crop: Boolean, look: Look?, manual: RectF?): Bitmap? = mutex.withLock {
        if (closed || index !in 0 until pageCount || maxW <= 0 || maxH <= 0) return@withLock null
        withContext(Dispatchers.IO) {
            try {
                val page = renderer.openPage(index)
                val bmp = try {
                    val pw = page.width.toFloat()
                    val ph = page.height.toFloat()
                    val r = if (manual != null) manual else if (crop) {
                        if (cropCache.containsKey(index)) cropCache[index] else detectCrop(page).also { cropCache[index] = it }
                    } else {
                        null
                    }
                    shown[index] = r ?: RectF(0f, 0f, 1f, 1f)
                    val cx = (r?.left ?: 0f) * pw
                    val cy = (r?.top ?: 0f) * ph
                    val cw = ((r?.width() ?: 1f) * pw).coerceAtLeast(1f)
                    val ch = ((r?.height() ?: 1f) * ph).coerceAtLeast(1f)
                    val s = minOf(maxW / cw, maxH / ch)
                    val w = (cw * s).toInt().coerceAtLeast(1)
                    val h = (ch * s).toInt().coerceAtLeast(1)
                    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    b.eraseColor(Color.WHITE)
                    val m = Matrix()
                    m.postScale(s, s)
                    m.postTranslate(-cx * s, -cy * s)
                    page.render(b, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    b
                } finally {
                    page.close()
                }
                if (look == null) {
                    bmp
                } else {
                    val a = if (look.deskew) angles[index] ?: Enhance.skewAngle(bmp).also { angles[index] = it } else 0f
                    Enhance.apply(bmp, look, a)
                }
            } catch (e: Throwable) {
                android.util.Log.w("Estante", "render falhou p=$index crop=$crop look=$look", e)
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
    @Volatile private var shown = RectF(0f, 0f, 1f, 1f)
    override fun shownRect(index: Int): RectF = shown
    override suspend fun render(index: Int, maxW: Int, maxH: Int, crop: Boolean, look: Look?, manual: RectF?): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                val cut = manual != null || crop
                val big = decodeImage(file, if (cut) maxW * 2 else maxW, if (cut) maxH * 2 else maxH)
                val r = manual ?: if (crop) Crop.detect(big) else null
                shown = r?.let { RectF(it) } ?: RectF(0f, 0f, 1f, 1f)
                val b = if (r == null) {
                    big
                } else {
                    val x = (r.left * big.width).toInt().coerceIn(0, big.width - 1)
                    val y = (r.top * big.height).toInt().coerceIn(0, big.height - 1)
                    val w = (r.width() * big.width).toInt().coerceIn(1, big.width - x)
                    val h = (r.height() * big.height).toInt().coerceIn(1, big.height - y)
                    Bitmap.createBitmap(big, x, y, w, h)
                }
                if (look == null) b else Enhance.apply(b, look)
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
    private val shown = java.util.concurrent.ConcurrentHashMap<Int, RectF>()
    override fun shownRect(index: Int): RectF = shown[index] ?: RectF(0f, 0f, 1f, 1f)
    override suspend fun render(index: Int, maxW: Int, maxH: Int, crop: Boolean, look: Look?, manual: RectF?): Bitmap? {
        if (index !in files.indices) return null
        val d = ImageDoc(files[index])
        val b = d.render(0, maxW, maxH, crop, look, manual)
        shown[index] = d.shownRect(0)
        return b
    }
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
