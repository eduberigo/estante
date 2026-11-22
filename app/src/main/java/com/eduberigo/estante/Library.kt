package com.eduberigo.estante

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Uma música no setlist: o arquivo e, se quiser, um nome só para o setlist (o arquivo não muda). */
data class SetItem(val path: String, val name: String? = null) {
    fun label(): String = name ?: File(path).nameWithoutExtension
}

/** Setlist: só uma lista ordenada de caminhos. Nenhum arquivo é copiado. */
data class Setlist(val id: String, val name: String, val items: List<SetItem>)

/** Uma vez que a partitura ficou aberta tempo suficiente para contar como "tocada". */
data class Play(val time: Long, val path: String, val seconds: Int)

/** Setlists automáticos, calculados a partir do histórico. */
data class AutoSetlist(val id: String, val name: String, val items: List<String>)

class Library(ctx: Context) {
    private val setFile = File(ctx.filesDir, "setlists.json")
    private val histFile = File(ctx.filesDir, "historico.tsv")

    var setlists by mutableStateOf(loadSetlists())
        private set
    var history by mutableStateOf(loadHistory())
        private set

    // ---------- setlists ----------

    fun get(id: String): Setlist? = setlists.firstOrNull { it.id == id }

    fun create(name: String, paths: List<String> = emptyList()): Setlist {
        val s = Setlist(UUID.randomUUID().toString(), name.trim().ifEmpty { "Novo setlist" }, paths.map { SetItem(it) })
        setlists = setlists + s
        saveSetlists()
        return s
    }

    fun rename(id: String, name: String) = update(id) { it.copy(name = name.trim().ifEmpty { it.name }) }

    fun delete(id: String) {
        setlists = setlists.filter { it.id != id }
        saveSetlists()
    }

    fun add(id: String, path: String) = update(id) { it.copy(items = it.items + SetItem(path)) }

    fun renameItem(id: String, index: Int, name: String?) = update(id) {
        if (index !in it.items.indices) {
            it
        } else {
            val m = it.items.toMutableList()
            m[index] = m[index].copy(name = name?.trim()?.ifEmpty { null })
            it.copy(items = m)
        }
    }

    fun removeAt(id: String, index: Int) = update(id) {
        if (index !in it.items.indices) it else it.copy(items = it.items.toMutableList().also { m -> m.removeAt(index) })
    }

    fun move(id: String, index: Int, delta: Int) = update(id) {
        val j = index + delta
        if (index !in it.items.indices || j !in it.items.indices) {
            it
        } else {
            val m = it.items.toMutableList()
            val x = m.removeAt(index)
            m.add(j, x)
            it.copy(items = m)
        }
    }

    private fun update(id: String, f: (Setlist) -> Setlist) {
        setlists = setlists.map { if (it.id == id) f(it) else it }
        saveSetlists()
    }

    private fun loadSetlists(): List<Setlist> = try {
        if (!setFile.exists()) {
            emptyList()
        } else {
            val arr = JSONArray(setFile.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val items = o.getJSONArray("items")
                Setlist(
                    o.getString("id"),
                    o.getString("name"),
                    (0 until items.length()).map { k ->
                        val x = items.get(k)
                        if (x is JSONObject) {
                            SetItem(x.getString("path"), x.optString("name").ifEmpty { null })
                        } else {
                            SetItem(x.toString())
                        }
                    },
                )
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveSetlists() {
        try {
            val arr = JSONArray()
            for (s in setlists) {
                arr.put(
                    JSONObject()
                        .put("id", s.id)
                        .put("name", s.name)
                        .put(
                            "items",
                            JSONArray().also { a ->
                                s.items.forEach { it2 ->
                                    val o = JSONObject().put("path", it2.path)
                                    if (it2.name != null) o.put("name", it2.name)
                                    a.put(o)
                                }
                            },
                        )
                )
            }
            val tmp = File(setFile.path + ".tmp")
            tmp.writeText(arr.toString(1))
            tmp.renameTo(setFile)
        } catch (_: Exception) {
        }
    }

    /** Depois de mover arquivos/pastas, aponta os setlists para os caminhos novos. */
    fun relink(moved: Map<String, String>) {
        if (moved.isEmpty()) return
        fun map(p: String): String {
            moved[p]?.let { return it }
            for ((a, b) in moved) if (p.startsWith("$a/")) return b + p.removePrefix(a)
            return p
        }
        setlists = setlists.map { sl -> sl.copy(items = sl.items.map { it.copy(path = map(it.path)) }) }
        saveSetlists()
    }

    // ---------- histórico ----------

    fun recordPlay(path: String, seconds: Int) {
        val p = Play(System.currentTimeMillis(), path, seconds)
        history = history + p
        try {
            histFile.appendText("${p.time}\t${p.seconds}\t${p.path}\n")
        } catch (_: Exception) {
        }
    }

    private fun loadHistory(): List<Play> = try {
        if (!histFile.exists()) {
            emptyList()
        } else {
            histFile.readLines().mapNotNull { line ->
                val parts = line.split('\t', limit = 3)
                if (parts.size < 3) null else Play(parts[0].toLongOrNull() ?: 0L, parts[2], parts[1].toIntOrNull() ?: 0)
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun playCount(path: String): Int = history.count { it.path == path }

    // ---------- automáticos ----------

    fun autoSetlists(): List<AutoSetlist> {
        if (history.isEmpty()) return emptyList()
        val counts = history.groupingBy { it.path }.eachCount()
        val top = counts.entries.sortedByDescending { it.value }.map { it.key }
        val out = mutableListOf(AutoSetlist("auto:top", "Mais tocadas", top.take(40)))
        val byArtist = top.mapNotNull { p ->
            val artist = Meta.guess(File(p).nameWithoutExtension).artist ?: return@mapNotNull null
            artist to p
        }.groupBy({ it.first }, { it.second })
        byArtist.entries
            .filter { it.value.size >= 2 }
            .sortedByDescending { e -> e.value.sumOf { counts[it] ?: 0 } }
            .take(12)
            .forEach { e -> out.add(AutoSetlist("auto:artist:${e.key}", e.key, e.value)) }
        return out
    }
}

/**
 * Palpite de artista e título a partir do nome do arquivo. É aproximado:
 * - "Título - Artista - Código - Gênero - Tom - BPM" (padrão renomeado) → título primeiro;
 * - "artista - música" (nomes curtos) → decide pela frequência na biblioteca: quem se repete é o artista.
 */
object Meta {
    data class Guess(val artist: String?, val title: String)

    private val keyRe = Regex("^[A-Ga-g](#|b|♯|♭)?(m|min|maj|M|maior|menor)?$")
    private val bpmRe = Regex("^\\d{2,3}\\s?(bpm)?$", RegexOption.IGNORE_CASE)
    private val longNum = Regex("^\\d{5,}$")
    private val leadingId = Regex("^\\d{5,}[-_ ]+")
    private val trailingCopy = Regex("(_\\d+|\\s*\\(\\d+\\))$")
    private val genres = setOf(
        "jazz", "jazz ballad", "jazz vocal", "brasil", "bossa", "samba", "mpb", "choro", "pop", "rock",
        "pop rock", "classico", "clássico", "gospel", "latin", "blues", "funk", "soul", "r&b", "country",
        "forro", "forró", "sertanejo", "tango", "bolero", "valsa", "waltz", "ballad",
    )

    data class Info(val artist: String?, val title: String, val genre: String?, val key: String?, val bpm: Int?)

    private fun normKey(p: String): String {
        val t = p.trim().replace("♯", "#").replace("♭", "b")
        val k = t.substring(0, 1).uppercase() + t.substring(1)
        return k.replace("menor", "m").replace("min", "m").replace("maior", "").replace("maj", "")
    }

    /** Artista, título, gênero, tom e BPM tirados do nome do arquivo (estimativa). */
    fun info(name: String): Info {
        val n = name.replace(leadingId, "").replace(trailingCopy, "").trim()
        val raw = if (n.contains(" - ")) n.split(" - ").map { it.trim() }.filter { it.isNotEmpty() } else listOf(n)
        val key = raw.firstOrNull { keyRe.matches(it) }?.let { normKey(it) }
        val bpm = bpmOf(n)
        val genre = raw.firstOrNull { p ->
            val low = p.lowercase()
            low in genres || low.startsWith("brasil ") || low.startsWith("jazz ")
        }?.lowercase()
        val g = guess(name)
        return Info(g.artist, g.title, genre, key, bpm)
    }

    /** BPM escrito no nome do arquivo (ex.: "... - 95.pdf" ou "95 bpm"), se houver. */
    fun bpmOf(name: String): Int? =
        name.split(" - ").map { it.trim() }.firstNotNullOfOrNull { p ->
            if (bpmRe.matches(p)) p.filter(Char::isDigit).toIntOrNull()?.takeIf { it in 30..300 } else null
        }

    /** Frequência de cada trecho na biblioteca (preenchida a partir do índice de busca). */
    @Volatile var partFreq: Map<String, Int> = emptyMap()

    fun parts(name: String): Pair<List<String>, Int> {
        val n = name.replace(leadingId, "").replace(trailingCopy, "").trim()
        val raw = if (n.contains(" - ")) n.split(" - ") else listOf(n.replace('_', ' '))
        val all = raw.map { it.trim() }.filter { it.isNotEmpty() }
        val useful = all.filter { p ->
            val low = p.lowercase()
            !keyRe.matches(p) && !bpmRe.matches(p) && !longNum.matches(p) &&
                low !in genres && !low.startsWith("brasil ") && !low.startsWith("jazz ")
        }
        return useful to all.size
    }

    fun guess(name: String): Guess {
        val (ps, total) = parts(name)
        if (ps.isEmpty()) return Guess(null, name)
        if (ps.size == 1) return Guess(null, ps[0])
        val a = ps[0]
        val b = ps[1]
        // Padrão completo (com código, gênero, tom, BPM): Título - Artista.
        if (total >= 4) return Guess(b, a)
        val fa = partFreq[Fs.norm(a)] ?: 0
        val fb = partFreq[Fs.norm(b)] ?: 0
        return when {
            fb > fa -> Guess(b, a)
            else -> Guess(a, b)
        }
    }

    /** Conta quantas vezes cada trecho aparece nos nomes da biblioteca. */
    fun buildFreq(names: Sequence<String>) {
        val m = HashMap<String, Int>()
        for (nm in names) {
            val (ps, _) = parts(nm)
            for (p in ps.take(2)) {
                val k = Fs.norm(p)
                m[k] = (m[k] ?: 0) + 1
            }
        }
        partFreq = m
    }
}
