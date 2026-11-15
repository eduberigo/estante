package com.eduberigo.estante

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
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
    suspend fun render(index: Int, maxW: Int, maxH: Int): Bitmap?
    fun close()
}

fun openDoc(f: File): Doc? = try {
    when (Fs.kindOf(f)) {
        Kind.PDF -> PdfDoc(f)
        Kind.IMAGE -> ImageDoc(f)
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

    override suspend fun render(index: Int, maxW: Int, maxH: Int): Bitmap? = mutex.withLock {
        if (closed || index !in 0 until pageCount || maxW <= 0 || maxH <= 0) return@withLock null
        withContext(Dispatchers.IO) {
            try {
                val page = renderer.openPage(index)
                try {
                    val s = minOf(maxW.toFloat() / page.width, maxH.toFloat() / page.height)
                    val w = (page.width * s).toInt().coerceAtLeast(1)
                    val h = (page.height * s).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
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
    override suspend fun render(index: Int, maxW: Int, maxH: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            try { decodeImage(file, maxW, maxH) } catch (e: Throwable) { null }
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
