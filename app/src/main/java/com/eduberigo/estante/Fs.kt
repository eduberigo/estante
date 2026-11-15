package com.eduberigo.estante

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.storage.StorageManager
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.Collator
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Kind { DIR, PDF, IMAGE, OTHER }

/** Um item do explorador. Tamanho e data são lidos uma vez, fora da thread de tela. */
data class Entry(
    val file: File,
    val kind: Kind,
    val size: Long,
    val modified: Long,
) {
    val name: String get() = file.name
    val path: String get() = file.absolutePath
    val title: String get() = if (kind == Kind.DIR) file.name else file.nameWithoutExtension
}

data class Volume(val label: String, val dir: File)

object Fs {
    private val imageExt = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif")

    fun kindOf(f: File, isDir: Boolean = f.isDirectory): Kind = when {
        isDir -> Kind.DIR
        f.extension.equals("pdf", ignoreCase = true) -> Kind.PDF
        f.extension.lowercase() in imageExt -> Kind.IMAGE
        else -> Kind.OTHER
    }

    fun entry(f: File): Entry {
        val dir = f.isDirectory
        return Entry(f, kindOf(f, dir), if (dir) 0L else f.length(), f.lastModified())
    }

    /** Pastas primeiro, depois arquivos, em ordem natural (2 antes de 10, acentos ignorados). */
    fun list(dir: File): List<Entry> {
        val files = dir.listFiles() ?: return emptyList()
        return files.asSequence()
            .filter { !it.name.startsWith(".") }
            .map { entry(it) }
            .sortedWith(entryOrder)
            .toList()
    }

    val entryOrder: Comparator<Entry> = Comparator { a, b ->
        val da = if (a.kind == Kind.DIR) 0 else 1
        val db = if (b.kind == Kind.DIR) 0 else 1
        if (da != db) da - db else NaturalOrder.compare(a.name, b.name)
    }

    fun volumes(ctx: Context): List<Volume> {
        val sm = ctx.getSystemService(StorageManager::class.java)
        val list = sm?.storageVolumes?.mapNotNull { v ->
            val d = v.directory ?: return@mapNotNull null
            Volume(v.getDescription(ctx), d)
        }.orEmpty()
        return list.ifEmpty {
            listOf(Volume("Armazenamento interno", Environment.getExternalStorageDirectory()))
        }
    }

    fun volumeOf(dir: File, volumes: List<Volume>): Volume? {
        val p = dir.absolutePath
        return volumes
            .filter { p == it.dir.absolutePath || p.startsWith(it.dir.absolutePath + "/") }
            .maxByOrNull { it.dir.absolutePath.length }
    }

    private val accents = Regex("\\p{Mn}+")

    /** Minúsculas e sem acento, para busca. */
    fun norm(s: String): String =
        accents.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

    fun humanSize(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024L * 1024 -> String.format(Locale("pt", "BR"), "%.0f KB", b / 1024.0)
        b < 1024L * 1024 * 1024 -> String.format(Locale("pt", "BR"), "%.1f MB", b / 1048576.0)
        else -> String.format(Locale("pt", "BR"), "%.2f GB", b / 1073741824.0)
    }

    private val dateFmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR"))
    fun humanDate(ms: Long): String = synchronized(dateFmt) { dateFmt.format(Date(ms)) }

    fun openExternal(ctx: Context, f: File) {
        try {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            val mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(f.extension.lowercase(Locale.ROOT)) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(intent, f.name))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, "Nenhum app abre este arquivo", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(ctx, "Não foi possível abrir: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

/** Ordem natural: "Faixa 2" antes de "Faixa 10"; acentos e maiúsculas não importam. */
object NaturalOrder : Comparator<String> {
    private val collator: Collator =
        Collator.getInstance(Locale("pt", "BR")).apply { strength = Collator.PRIMARY }
    private val token = Regex("\\d+|\\D+")

    override fun compare(a: String, b: String): Int {
        val ta = token.findAll(a).map { it.value }.toList()
        val tb = token.findAll(b).map { it.value }.toList()
        val n = minOf(ta.size, tb.size)
        for (i in 0 until n) {
            val x = ta[i]
            val y = tb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                val bx = x.trimStart('0')
                val by = y.trimStart('0')
                if (bx.length != by.length) bx.length - by.length else bx.compareTo(by)
            } else {
                synchronized(collator) { collator.compare(x, y) }
            }
            if (c != 0) return c
        }
        return ta.size - tb.size
    }
}
