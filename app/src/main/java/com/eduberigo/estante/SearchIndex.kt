package com.eduberigo.estante

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Índice de nomes de arquivo para a busca, cobrindo todas as pastas do Início.
 * A chave inclui o nome da pasta do Início e as subpastas, então
 * "casamento perfect" acha "Casamentos/Perfect - Ed Sheeran ...pdf".
 */
object SearchIndex {
    class Item(val path: String, val key: String)

    @Volatile private var sig: String? = null
    @Volatile private var items: List<Item> = emptyList()

    val size: Int get() = items.size

    private fun signature(roots: List<File>) = roots.joinToString("|") { it.absolutePath }

    private fun make(roots: List<File>, path: String): Item {
        val root = roots
            .filter { path.startsWith(it.absolutePath + "/") }
            .maxByOrNull { it.absolutePath.length }
        val rel = if (root != null) {
            root.name + "/" + path.removePrefix(root.absolutePath).trimStart('/')
        } else {
            path
        }
        return Item(path, Fs.norm(rel))
    }

    suspend fun ensure(ctx: Context, roots: List<File>, force: Boolean, onProgress: (Int) -> Unit) {
        withContext(Dispatchers.IO) {
            val s = signature(roots)
            if (!force && sig == s && items.isNotEmpty()) return@withContext
            val cache = File(ctx.filesDir, "index.txt")
            if (!force && cache.exists()) {
                val lines = try { cache.readLines() } catch (e: Exception) { emptyList() }
                if (lines.isNotEmpty() && lines[0] == s) {
                    items = lines.drop(1).filter { it.isNotBlank() }.map { make(roots, it) }
                    sig = s
                    return@withContext
                }
            }
            val seen = HashSet<String>()
            val out = ArrayList<Item>()
            for (root in roots) {
                root.walkTopDown()
                    .onEnter { it == root || !it.name.startsWith(".") }
                    .forEach { f ->
                        ensureActive()
                        if (f.isFile && !f.name.startsWith(".") && seen.add(f.absolutePath)) {
                            out.add(make(roots, f.absolutePath))
                            if (out.size % 250 == 0) onProgress(out.size)
                        }
                    }
            }
            items = out
            sig = s
            try {
                cache.bufferedWriter().use { w ->
                    w.write(s)
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
