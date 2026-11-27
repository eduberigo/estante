package com.eduberigo.estante

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * Pastas e arquivos tirados do app (não apagados do aparelho).
 * Uma pasta fora do app leva junto tudo o que está dentro dela.
 * Somem das listas, da busca, da navegação por artista/título e da classificação.
 */
object Excluded {
    private var file: File? = null
    var set by mutableStateOf<Set<String>>(emptySet())
        private set

    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "fora-do-app.txt")
        file = f
        set = try {
            if (f.exists()) f.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() else emptySet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    /** O caminho ou alguma pasta acima dele está fora do app. */
    fun has(path: String): Boolean {
        val s = set
        if (s.isEmpty()) return false
        var p = path
        while (p.length > 1) {
            if (p in s) return true
            val i = p.lastIndexOf('/')
            if (i <= 0) break
            p = p.substring(0, i)
        }
        return false
    }

    fun add(paths: Collection<String>) {
        if (paths.isEmpty()) return
        set = set + paths
        save()
    }

    fun remove(paths: Collection<String>) {
        set = set - paths.toSet()
        save()
    }

    /** Depois de mover/renomear pelo app, a exclusão acompanha o caminho novo. */
    fun relink(moved: Map<String, String>) {
        if (moved.isEmpty() || set.isEmpty()) return
        fun mapP(p: String): String {
            moved[p]?.let { return it }
            for ((a, b) in moved) if (p.startsWith("$a/")) return b + p.removePrefix(a)
            return p
        }
        set = set.map(::mapP).toSet()
        save()
    }

    /** Quantos itens desta pasta (filhos diretos) estão fora do app. */
    fun countIn(dir: File): Int {
        val d = dir.absolutePath
        return set.count { File(it).parent == d }
    }

    private fun save() {
        val f = file ?: return
        try {
            f.writeText(set.sorted().joinToString("\n"))
        } catch (_: Exception) {
        }
    }
}
