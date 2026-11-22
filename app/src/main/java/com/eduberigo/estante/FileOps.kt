package com.eduberigo.estante

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Copiar, mover e mandar para a lixeira. Apagar nunca é definitivo: vai para a pasta Estante-Lixeira. */
object FileOps {
    data class Report(val done: Int, val failed: List<String>, val moved: Map<String, String>)

    /** Nome livre no destino: "x.pdf", "x (2).pdf", ... */
    private fun free(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var n = 2
        while (f.exists()) {
            f = File(dir, "$base ($n)$ext")
            n++
        }
        return f
    }

    suspend fun copy(paths: List<String>, dest: File): Report = withContext(Dispatchers.IO) {
        var ok = 0
        val fail = mutableListOf<String>()
        for (p in paths) {
            val src = File(p)
            try {
                if (dest.absolutePath == src.absolutePath || dest.absolutePath.startsWith(src.absolutePath + "/")) {
                    fail.add(src.name)
                    continue
                }
                val dst = free(dest, src.name)
                if (src.isDirectory) src.copyRecursively(dst) else src.copyTo(dst)
                ok++
            } catch (e: Exception) {
                fail.add(src.name)
            }
        }
        Report(ok, fail, emptyMap())
    }

    suspend fun move(paths: List<String>, dest: File): Report = withContext(Dispatchers.IO) {
        var ok = 0
        val fail = mutableListOf<String>()
        val moved = LinkedHashMap<String, String>()
        for (p in paths) {
            val src = File(p)
            try {
                if (src.parentFile?.absolutePath == dest.absolutePath) continue
                if (dest.absolutePath == src.absolutePath || dest.absolutePath.startsWith(src.absolutePath + "/")) {
                    fail.add(src.name)
                    continue
                }
                val dst = free(dest, src.name)
                // Mesmo armazenamento: renomeia (instantâneo). Entre memória e cartão: copia e apaga.
                if (!src.renameTo(dst)) {
                    if (src.isDirectory) src.copyRecursively(dst) else src.copyTo(dst)
                    if (!src.deleteRecursively()) throw IllegalStateException("não apagou a origem")
                }
                moved[src.absolutePath] = dst.absolutePath
                ok++
            } catch (e: Exception) {
                fail.add(src.name)
            }
        }
        Report(ok, fail, moved)
    }

    /** Lixeira na raiz do mesmo armazenamento (memória ou cartão), para não copiar entre eles. */
    fun trashDir(f: File, volumes: List<Volume>): File {
        val root = Fs.volumeOf(f, volumes)?.dir ?: File("/storage/emulated/0")
        return File(root, "Estante-Lixeira")
    }

    suspend fun trash(paths: List<String>, volumes: List<Volume>): Report = withContext(Dispatchers.IO) {
        var ok = 0
        val fail = mutableListOf<String>()
        val moved = LinkedHashMap<String, String>()
        for (p in paths) {
            val src = File(p)
            try {
                val dir = trashDir(src, volumes).apply { mkdirs() }
                val dst = free(dir, src.name)
                if (!src.renameTo(dst)) {
                    if (src.isDirectory) src.copyRecursively(dst) else src.copyTo(dst)
                    if (!src.deleteRecursively()) throw IllegalStateException("não apagou")
                }
                moved[src.absolutePath] = dst.absolutePath
                ok++
            } catch (e: Exception) {
                fail.add(src.name)
            }
        }
        Report(ok, fail, moved)
    }
}
