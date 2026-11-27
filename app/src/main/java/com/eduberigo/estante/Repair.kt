package com.eduberigo.estante

import android.content.Context
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Música de setlist trocada automaticamente por outra cópia do mesmo arquivo. */
data class Swap(
    val setlistId: String,
    val setlist: String,
    val label: String,
    val old: String,
    val new: String,
    /** A cópia apagada tinha anotações (elas continuam guardadas no app). */
    val oldNotes: Boolean,
    val time: Long,
)

/** Música de setlist cujo arquivo sumiu e não tem outra cópia no acervo. */
data class Missing(val setlistId: String, val setlist: String, val index: Int, val label: String, val old: String)

/**
 * Setlists com arquivos apagados ou movidos fora do app:
 * 1) procura no acervo (pastas do Início) outro arquivo com o mesmo nome e troca sozinho;
 * 2) o que não achar fica marcado no app e listado em Documentos/Estante/"Setlists - músicas faltando.txt".
 * Arquivos de um cartão de memória que não está no aparelho agora são deixados como estão.
 */
object SetlistRepair {
    private var file: File? = null
    var swaps by mutableStateOf<List<Swap>>(emptyList())
        private set
    var missing by mutableStateOf<List<Missing>>(emptyList())
        private set

    val txtFile: File
        get() = File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Estante"), "Setlists - músicas faltando.txt")

    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "setlist-trocas.json")
        file = f
        swaps = try {
            if (!f.exists()) emptyList() else JSONArray(f.readText()).let { a ->
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    Swap(
                        o.getString("sid"), o.getString("setlist"), o.getString("label"),
                        o.getString("old"), o.getString("new"), o.optBoolean("notes"), o.optLong("time"),
                    )
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private class Plan(val moves: Map<String, String>, val swaps: List<Swap>, val missing: List<Missing>)

    /** Confere todos os setlists. Rápido: só olha se cada arquivo existe e procura pelo nome no índice. */
    suspend fun check(ctx: Context, lib: Library) {
        if (SearchIndex.size == 0) return
        val sets = lib.setlists
        val plan = withContext(Dispatchers.IO) {
            val vols = Fs.volumes(ctx)
            val moves = HashMap<String, String>()
            val newSwaps = mutableListOf<Swap>()
            val miss = mutableListOf<Missing>()
            for (s in sets) {
                s.items.forEachIndexed { i, it ->
                    val f = File(it.path)
                    if (f.exists()) return@forEachIndexed
                    // Cartão de memória fora do aparelho: não mexe.
                    if (Fs.volumeOf(f, vols) == null) return@forEachIndexed
                    val target = moves[it.path] ?: findCopy(f)
                    if (target != null) {
                        moves[it.path] = target
                        newSwaps.add(
                            Swap(s.id, s.name, it.label(), it.path, target, !AnnStore.load(ctx, it.path).isEmpty(), System.currentTimeMillis())
                        )
                    } else {
                        miss.add(Missing(s.id, s.name, i, it.label(), it.path))
                    }
                }
            }
            Plan(moves, newSwaps, miss)
        }
        if (plan.moves.isNotEmpty()) lib.replaceMissing(plan.moves)
        if (plan.swaps.isNotEmpty()) {
            swaps = (swaps + plan.swaps).takeLast(200)
            save()
        }
        missing = plan.missing
        withContext(Dispatchers.IO) { writeTxt() }
    }

    /** Outra cópia do mesmo arquivo (mesmo nome) no acervo; prefere a pasta mais parecida com a antiga. */
    fun findCopy(old: File): String? {
        val cands = SearchIndex.byName(old.name)
            .filter { it != old.absolutePath && !it.contains("/Estante-Lixeira/") && !Excluded.has(it) && File(it).isFile }
        if (cands.isEmpty()) return null
        val oldParts = old.parent.orEmpty().split('/').filter { it.isNotEmpty() }
        fun score(p: String): Int {
            val parts = File(p).parent.orEmpty().split('/').filter { it.isNotEmpty() }
            var s = 0
            if (parts.lastOrNull()?.let { Fs.norm(it) } == oldParts.lastOrNull()?.let { Fs.norm(it) }) s += 1000
            s += parts.zip(oldParts).takeWhile { (a, b) -> a == b }.size * 10
            s += parts.toSet().intersect(oldParts.toSet()).size
            return s
        }
        return cands.sortedWith(compareByDescending<String> { score(it) }.thenComparator { a, b -> NaturalOrder.compare(a, b) }).first()
    }

    /** "OK, conferi": limpa a lista de trocas. */
    fun clearSwaps() {
        swaps = emptyList()
        save()
        try { writeTxt() } catch (_: Exception) {}
    }

    /** A troca foi conferida (ou desfeita): sai da lista. */
    fun forgetSwap(s: Swap) {
        swaps = swaps - s
        save()
        try { writeTxt() } catch (_: Exception) {}
    }

    /** Traz para a cópia nova as anotações (âncoras, saltos, dedilhados, recortes…) da cópia apagada. */
    fun copyNotes(ctx: Context, s: Swap) {
        AnnStore.save(ctx, s.new, AnnStore.load(ctx, s.old))
    }

    private fun short(p: String): String = p.substringAfter("/emulated/0/").substringAfter("/storage/")

    private fun writeTxt() {
        val f = txtFile
        val miss = missing
        val sw = swaps
        if (miss.isEmpty() && sw.isEmpty()) {
            if (f.exists()) f.delete()
            return
        }
        f.parentFile?.mkdirs()
        val text = buildString {
            append("Estante — músicas dos setlists que precisam de atenção\n")
            append("Atualizado em ").append(Fs.humanDate(System.currentTimeMillis())).append("\n\n")
            if (miss.isNotEmpty()) {
                append("NÃO ENCONTRADAS — substituir no app (Início → aviso dos setlists → Substituir)\n")
                miss.groupBy { it.setlist }.forEach { (name, list) ->
                    append("\nSetlist \"").append(name).append("\"\n")
                    list.forEach { m ->
                        append("  ").append(m.index + 1).append(". ").append(m.label).append('\n')
                        append("     era: ").append(short(m.old)).append('\n')
                    }
                }
                append('\n')
            }
            if (sw.isNotEmpty()) {
                append("TROCADAS SOZINHAS POR OUTRA CÓPIA DO MESMO ARQUIVO — confira\n")
                sw.groupBy { it.setlist }.forEach { (name, list) ->
                    append("\nSetlist \"").append(name).append("\"\n")
                    list.forEach { s ->
                        append("  ").append(s.label).append('\n')
                        append("     de:   ").append(short(s.old)).append('\n')
                        append("     para: ").append(short(s.new)).append('\n')
                        if (s.oldNotes) append("     (a cópia apagada tinha anotações; dá para trazê-las no app)\n")
                    }
                }
            }
        }
        f.writeText(text)
    }

    private fun save() {
        val f = file ?: return
        try {
            val a = JSONArray()
            swaps.forEach { s ->
                a.put(
                    JSONObject().put("sid", s.setlistId).put("setlist", s.setlist).put("label", s.label)
                        .put("old", s.old).put("new", s.new).put("notes", s.oldNotes).put("time", s.time)
                )
            }
            f.writeText(a.toString())
        } catch (_: Exception) {
        }
    }
}
