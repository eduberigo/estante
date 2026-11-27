package com.eduberigo.estante

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Environment
import androidx.core.content.FileProvider
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Gera PDFs e textos a partir de setlists e de imagens. Os originais nunca são alterados. */
object Export {
    /** Pasta de saída: Documentos/Estante/<nome>. */
    fun exportDir(name: String): File {
        val base = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Estante")
        return File(base, safe(name)).apply { mkdirs() }
    }

    fun safe(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "-").trim().ifEmpty { "Sem nome" }.take(120)

    /** Evita sobrescrever: "Nome.pdf", "Nome (2).pdf", ... */
    fun unique(dir: File, base: String, ext: String): File {
        var f = File(dir, "${safe(base)}.$ext")
        var n = 2
        while (f.exists()) {
            f = File(dir, "${safe(base)} ($n).$ext")
            n++
        }
        return f
    }

    private fun newDoc() = PDDocument(MemoryUsageSetting.setupTempFileOnly())

    /** Acrescenta uma imagem como página (largura A4, altura proporcional). */
    private fun addImagePage(doc: PDDocument, img: File) {
        val bmp: Bitmap = decodeImage(img, 2480, 3508)
        val w = 595f
        val h = w * bmp.height / bmp.width
        val page = PDPage(PDRectangle(w, h))
        doc.addPage(page)
        val x = JPEGFactory.createFromImage(doc, bmp, 0.9f)
        PDPageContentStream(doc, page).use { cs -> cs.drawImage(x, 0f, 0f, w, h) }
        bmp.recycle()
    }

    /** Várias imagens (ex.: páginas 3 a 5 de um songbook) em um PDF. */
    suspend fun imagesToPdf(images: List<File>, out: File) = withContext(Dispatchers.IO) {
        newDoc().use { doc ->
            images.forEach { addImagePage(doc, it) }
            doc.save(out)
        }
    }

    data class Result(val files: List<File>, val skipped: List<String>)

    /**
     * Recorte gravado no arquivo. PDF: muda só a "caixa de corte" de cada página
     * (as notas continuam vetoriais, sem perder qualidade). Imagem: corta os pixels.
     * [rectFor] dá o retângulo (frações da página como aparece na tela) de cada página, ou null para não mexer.
     */
    suspend fun cropFile(src: File, out: File, rectFor: (Int) -> android.graphics.RectF?) = withContext(Dispatchers.IO) {
        when (Fs.kindOf(src)) {
            Kind.PDF -> {
                PDDocument.load(src, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                    if (doc.isEncrypted) doc.setAllSecurityToBeRemoved(true)
                    for (i in 0 until doc.numberOfPages) {
                        val r = rectFor(i) ?: continue
                        val page = doc.getPage(i)
                        val box = page.cropBox
                        // Desfaz a rotação da página: (l,t,r,b) estão na orientação mostrada na tela.
                        val (u0, u1, v0, v1) = when (((page.rotation % 360) + 360) % 360) {
                            90 -> listOf(r.top, r.bottom, 1f - r.right, 1f - r.left)
                            180 -> listOf(1f - r.right, 1f - r.left, 1f - r.bottom, 1f - r.top)
                            270 -> listOf(1f - r.bottom, 1f - r.top, r.left, r.right)
                            else -> listOf(r.left, r.right, r.top, r.bottom)
                        }
                        val llx = box.lowerLeftX + u0 * box.width
                        val urx = box.lowerLeftX + u1 * box.width
                        val ury = box.upperRightY - v0 * box.height
                        val lly = box.upperRightY - v1 * box.height
                        page.cropBox = PDRectangle(llx, lly, urx - llx, ury - lly)
                    }
                    doc.save(out)
                }
            }
            Kind.IMAGE -> {
                val r = rectFor(0) ?: throw IllegalArgumentException("sem recorte")
                val big = decodeImage(src, 6000, 6000)
                val x = (r.left * big.width).toInt().coerceIn(0, big.width - 1)
                val y = (r.top * big.height).toInt().coerceIn(0, big.height - 1)
                val w = (r.width() * big.width).toInt().coerceIn(1, big.width - x)
                val h = (r.height() * big.height).toInt().coerceIn(1, big.height - y)
                val c = Bitmap.createBitmap(big, x, y, w, h)
                val fmt = if (out.extension.equals("png", true)) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                out.outputStream().use { c.compress(fmt, 95, it) }
            }
            else -> throw IllegalArgumentException("tipo não suportado")
        }
    }

    /**
     * Grava o modo legível no arquivo (página endireitada, fundo limpo, traço reforçado; recorte opcional).
     * PDF vira PDF de imagens; imagem vira JPG. A saída vai para [out].
     */
    suspend fun enhanceFile(
        src: File, out: File, crop: Boolean, look: Look, notes: FileNotes?, progress: (Int, Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        when (Fs.kindOf(src)) {
            Kind.PDF -> {
                val pdf = PdfDoc(src)
                try {
                    newDoc().use { doc ->
                        for (i in 0 until pdf.pageCount) {
                            progress(i + 1, pdf.pageCount)
                            val bmp = pdf.render(i, 1600, 2260, crop, look, notes?.cropFor(i))
                                ?: throw IllegalStateException("página ${i + 1} não renderizou")
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
                    pdf.close()
                }
            }
            Kind.IMAGE -> {
                progress(1, 1)
                val bmp = ImageDoc(src).render(0, 1600, 2260, crop, look, notes?.cropFor(0))
                    ?: throw IllegalStateException("imagem não abriu")
                out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                bmp.recycle()
            }
            else -> throw IllegalArgumentException("tipo não suportado")
        }
    }

    /** Um PDF por música: "01 - Nome.pdf". [o] = o que gravar dentro dos arquivos (anotações, recortes, ajustes). */
    suspend fun separate(ctx: Context, s: Setlist, o: Flatten.Opts): Result = withContext(Dispatchers.IO) {
        val dir = exportDir(s.name)
        val out = mutableListOf<File>()
        val skipped = mutableListOf<String>()
        s.items.forEachIndexed { i, it ->
            val src = File(it.path)
            val name = "%02d - %s".format(i + 1, it.label())
            try {
                if (Fs.kindOf(src) !in setOf(Kind.PDF, Kind.IMAGE)) {
                    skipped.add(it.label())
                } else {
                    val dst = unique(dir, name, "pdf")
                    Flatten.process(ctx, src, dst, o)
                    out.add(dst)
                }
            } catch (e: Throwable) {
                skipped.add(it.label())
            }
        }
        Result(out, skipped)
    }

    /** Fichário: tudo num PDF só, com marcadores (índice) por música. */
    suspend fun binder(ctx: Context, s: Setlist, o: Flatten.Opts): Result =
        merge(
            ctx,
            s.items.mapIndexed { i, it -> File(it.path) to "${i + 1}. ${it.label()}" },
            unique(exportDir(s.name), s.name, "pdf"),
            o,
        )

    /**
     * Junta PDFs e imagens em um PDF só ([dst]), na ordem dada, com um marcador por arquivo
     * (o texto de cada par). [o] = o que gravar dentro (anotações, recortes, ajustes).
     */
    suspend fun merge(ctx: Context, items: List<Pair<File, String>>, dst: File, o: Flatten.Opts): Result = withContext(Dispatchers.IO) {
        val skipped = mutableListOf<String>()
        val opened = mutableListOf<PDDocument>()
        val temps = mutableListOf<File>()
        val marks = mutableListOf<Pair<String, Int>>()
        try {
            newDoc().use { doc ->
                val merger = PDFMergerUtility()
                items.forEach { (src, label) ->
                    val start = doc.numberOfPages
                    try {
                        if (Fs.kindOf(src) !in setOf(Kind.PDF, Kind.IMAGE)) {
                            skipped.add(label)
                        } else {
                            val tmp = File(ctx.cacheDir, "juntar-${System.nanoTime()}.pdf")
                            temps.add(tmp)
                            Flatten.process(ctx, src, tmp, o)
                            val d = PDDocument.load(tmp, MemoryUsageSetting.setupTempFileOnly())
                            opened.add(d)
                            if (d.isEncrypted) d.setAllSecurityToBeRemoved(true)
                            merger.appendDocument(doc, d)
                        }
                    } catch (e: Throwable) {
                        skipped.add(label)
                    }
                    if (doc.numberOfPages > start) marks.add(label to start)
                }
                if (doc.numberOfPages == 0) throw IllegalStateException("nenhum arquivo pôde ser lido")
                // Índice só com os arquivos juntados (descarta marcadores que vieram dos originais).
                val outline = PDDocumentOutline()
                for ((title, page) in marks) {
                    val item = PDOutlineItem()
                    item.title = title
                    val dest = PDPageFitDestination()
                    dest.page = doc.getPage(page)
                    item.destination = dest
                    outline.addLast(item)
                }
                outline.openNode()
                doc.documentCatalog.documentOutline = outline
                doc.save(dst)
            }
        } finally {
            opened.forEach { try { it.close() } catch (_: Throwable) {} }
            temps.forEach { it.delete() }
        }
        Result(listOf(dst), skipped)
    }

    fun text(s: Setlist): String = buildString {
        append("Setlist ").append(s.name).append('\n').append('\n')
        s.items.forEachIndexed { i, it -> append(i + 1).append(". ").append(it.label()).append('\n') }
    }

    suspend fun txt(s: Setlist): File = withContext(Dispatchers.IO) {
        val f = unique(exportDir(s.name), s.name, "txt")
        f.writeText(text(s))
        f
    }

    fun shareText(ctx: Context, title: String, body: String) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .putExtra(Intent.EXTRA_TEXT, body)
        ctx.startActivity(Intent.createChooser(i, title))
    }

    fun shareFiles(ctx: Context, files: List<File>) {
        if (files.isEmpty()) return
        val uris = ArrayList(files.map { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", it) })
        val type = if (files.all { it.extension.equals("pdf", true) }) "application/pdf" else "*/*"
        val i = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).setType(type).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "Compartilhar"))
    }
}
