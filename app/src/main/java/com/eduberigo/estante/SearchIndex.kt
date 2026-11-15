package com.eduberigo.estante

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Índice de nomes de arquivo para a busca. Guarda o caminho relativo à pasta Início,
 * então "casamento perfect" acha "Casamentos/Perfect - Ed Sheeran ...pdf".
 */
object SearchIndex {
    class Item(val path: String, val key: String)

    @Volatile var root: String? = null
        private set
    @Volatile private var items: List<Item> = emptyList()

    val size: Int get() = items.size

    private fun make(rootPath: String, path: String): Item {
        val rel = path.removePrefix(rootPath).trimStart('/')
        return Item(path, Fs.norm(rel))
    }

    suspend fun ensure(ctx: Context, rootDir: File, force: Boolean, onProgress: (Int) -> Unit) {
        withContext(Dispatchers.IO) {
            val rootPath = rootDir.absolutePath
            if (!force && root == rootPath && items.isNotEmpty()) return@withContext
            val cache = File(ctx.filesDir, "index.txt")
            if (!force && cache.exists()) {
                val lines = try { cache.readLines() } catch (e: Exception) { emptyList() }
                if (lines.isNotEmpty() && lines[0] == rootPath) {
                    items = lines.drop(1).filter { it.isNotBlank() }.map { make(rootPath, it) }
                    root = rootPath
                    return@withContext
                }
            }
            val out = ArrayList<Item>()
            rootDir.walkTopDown()
                .onEnter { it == rootDir || !it.name.startsWith(".") }
                .forEach { f ->
                    ensureActive()
                    if (f.isFile && !f.name.startsWith(".")) {
                        out.add(make(rootPath, f.absolutePath))
                        if (out.size % 250 == 0) onProgress(out.size)
                    }
                }
            items = out
            root = rootPath
            try {
                cache.bufferedWriter().use { w ->
                    w.write(rootPath)
                    w.newLine()
                    for (item in out) {
                        w.write(item.path)
                        w.newLine()
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Todas as palavras precisam aparecer no caminho (pasta + nome), sem acento. */
    fun search(query: String, limit: Int = 400): List<Entry> {
        val tokens = Fs.norm(query).split(' ', '\t').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        return items.asSequence()
            .filter { item -> tokens.all { item.key.contains(it) } }
            .take(limit)
            .map { Fs.entry(File(it.path)) }
            .sortedWith(Comparator { a, b -> NaturalOrder.compare(a.name, b.name) })
            .toList()
    }
}
