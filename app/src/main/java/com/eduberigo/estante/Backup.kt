package com.eduberigo.estante

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Environment
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.Zip64Mode
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun stamp() = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())

fun backupsDir(): File =
    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Estante/Backups").apply { mkdirs() }

/**
 * Dados do app, sem as partituras: setlists, histórico, classificação, anotações (com âncoras, saltos,
 * recortes, metrônomo e ajustes de imagem), o que está fora do app, trocas automáticas dos setlists e
 * preferências (pastas do Início, favoritos, buscas recentes, modo de visualização…). Arquivo pequeno.
 */
object AppBackup {
    private fun sources(ctx: Context): List<Pair<String, File>> {
        val out = mutableListOf<Pair<String, File>>()
        File(ctx.filesDir, "setlists.json").takeIf { it.exists() }?.let { out.add("setlists.json" to it) }
        File(ctx.filesDir, "historico.tsv").takeIf { it.exists() }?.let { out.add("historico.tsv" to it) }
        File(ctx.filesDir, "classificacao.json").takeIf { it.exists() }?.let { out.add("classificacao.json" to it) }
        File(ctx.filesDir, "fora-do-app.txt").takeIf { it.exists() }?.let { out.add("fora-do-app.txt" to it) }
        File(ctx.filesDir, "setlist-trocas.json").takeIf { it.exists() }?.let { out.add("setlist-trocas.json" to it) }
        AnnStore.allFiles(ctx).forEach { out.add("anotacoes/${it.name}" to it) }
        File(ctx.dataDir, "shared_prefs/estante.xml").takeIf { it.exists() }?.let { out.add("prefs/estante.xml" to it) }
        return out
    }

    /** Escreve os dados do app dentro de um zip já aberto (usado também no backup das partituras). */
    fun writeInto(ctx: Context, zip: ZipArchiveOutputStream, prefix: String) {
        for ((name, f) in sources(ctx)) {
            val e = ZipArchiveEntry(f, prefix + name)
            zip.putArchiveEntry(e)
            f.inputStream().use { it.copyTo(zip) }
            zip.closeArchiveEntry()
        }
    }

    suspend fun create(ctx: Context): File = withContext(Dispatchers.IO) {
        val out = File(backupsDir(), "estante-app_${stamp()}.zip")
        ZipArchiveOutputStream(out).use { z ->
            z.setUseZip64(Zip64Mode.AsNeeded)
            writeInto(ctx, z, "")
        }
        out
    }

    fun list(): List<File> =
        backupsDir().listFiles()?.filter { it.name.startsWith("estante-app_") && it.name.endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() }.orEmpty()

    /** Restaura (substitui os dados atuais). Aceita o zip do app ou um backup de partituras (pasta _estante_app/). */
    suspend fun restore(ctx: Context, zipFile: File): Int = withContext(Dispatchers.IO) {
        var n = 0
        ZipFile.Builder().setFile(zipFile).get().use { zf ->
            val entries = zf.entries.toList()
            val prefix = if (entries.any { it.name.startsWith("_estante_app/") }) "_estante_app/" else ""
            val annDir = File(ctx.filesDir, "anotacoes")
            if (entries.any { it.name.startsWith(prefix + "anotacoes/") }) {
                annDir.listFiles()?.forEach { it.delete() }
            }
            for (e in entries) {
                if (e.isDirectory || !e.name.startsWith(prefix)) continue
                val rel = e.name.removePrefix(prefix)
                val dst = when {
                    rel in setOf("setlists.json", "historico.tsv", "classificacao.json", "fora-do-app.txt", "setlist-trocas.json") ->
                        File(ctx.filesDir, rel)
                    rel.startsWith("anotacoes/") && !rel.contains("..") -> File(annDir, rel.removePrefix("anotacoes/"))
                    rel == "prefs/estante.xml" -> File(ctx.dataDir, "shared_prefs/estante.xml")
                    else -> null
                } ?: continue
                dst.parentFile?.mkdirs()
                zf.getInputStream(e).use { inp -> dst.outputStream().use { inp.copyTo(it) } }
                n++
            }
        }
        n
    }
}

/** Progresso do backup das partituras (lido pela tela). */
data class BackupProgress(
    val running: Boolean = false,
    val done: Long = 0,
    val total: Long = 0,
    val files: Int = 0,
    val current: String = "",
    val result: String? = null,
)

object LibraryBackup {
    val progress = MutableStateFlow(BackupProgress())
    @Volatile var cancel = false

    data class Plan(val files: List<Pair<File, String>>, val bytes: Long)

    /** Lista tudo o que vai para o zip, com o caminho dentro do zip ("NomeDaPasta/..."). */
    fun plan(folders: List<File>): Plan {
        val out = ArrayList<Pair<File, String>>()
        var bytes = 0L
        for (root in folders) {
            root.walkTopDown().forEach { f ->
                if (f.isFile) {
                    out.add(f to root.name + "/" + f.absolutePath.removePrefix(root.absolutePath).trimStart('/'))
                    bytes += f.length()
                }
            }
        }
        return Plan(out, bytes)
    }

    fun start(ctx: Context, folders: List<String>, destDir: String) {
        val i = Intent(ctx, BackupService::class.java)
            .putExtra("folders", folders.toTypedArray())
            .putExtra("dest", destDir)
        ctx.startForegroundService(i)
    }
}

/**
 * Serviço em primeiro plano: continua rodando com a tela apagada ou com o app em segundo plano.
 * Os arquivos são guardados sem recompressão (PDF e JPG já são comprimidos): é mais rápido
 * e o tamanho final fica parecido com o da biblioteca.
 */
class BackupService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val chan = "backup"

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notif(text: String, pct: Int?, ongoing: Boolean): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(chan) == null) {
            nm.createNotificationChannel(NotificationChannel(chan, "Backup", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val b = Notification.Builder(this, chan)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Backup das partituras")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(ongoing)
        if (pct != null) b.setProgress(100, pct, false)
        return b.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel") {
            LibraryBackup.cancel = true
            return START_NOT_STICKY
        }
        val folders = intent?.getStringArrayExtra("folders")?.map { File(it) }.orEmpty()
        val dest = intent?.getStringExtra("dest")?.let { File(it) }
        startForeground(7, notif("Preparando…", 0, true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (folders.isEmpty() || dest == null || LibraryBackup.progress.value.running) {
            stopSelf()
            return START_NOT_STICKY
        }
        LibraryBackup.cancel = false
        scope.launch { run(folders, dest) }
        return START_NOT_STICKY
    }

    private fun run(folders: List<File>, destDir: File) {
        val p = LibraryBackup.progress
        p.value = BackupProgress(running = true, current = "Listando arquivos…")
        val nm = getSystemService(NotificationManager::class.java)
        var out: File? = null
        try {
            val plan = LibraryBackup.plan(folders)
            destDir.mkdirs()
            val free = destDir.usableSpace
            if (free < plan.bytes + 50L * 1024 * 1024) {
                throw IllegalStateException(
                    "Espaço insuficiente no destino: precisa de ${Fs.humanSize(plan.bytes)}, livre ${Fs.humanSize(free)}."
                )
            }
            val name = if (folders.size == 1) folders[0].name else "Partituras"
            out = File(destDir, "estante-${Export.safe(name)}_${stamp()}.zip")
            var done = 0L
            var lastPct = -1
            ZipArchiveOutputStream(out).use { z ->
                z.setUseZip64(Zip64Mode.AsNeeded)
                z.setMethod(ZipArchiveOutputStream.STORED)
                plan.files.forEachIndexed { i, (f, entryName) ->
                    if (LibraryBackup.cancel) throw InterruptedException("cancelado")
                    val e = ZipArchiveEntry(f, entryName)
                    z.putArchiveEntry(e)
                    f.inputStream().use { it.copyTo(z, 256 * 1024) }
                    z.closeArchiveEntry()
                    done += f.length()
                    val pct = if (plan.bytes > 0) (done * 100 / plan.bytes).toInt() else 100
                    p.value = BackupProgress(true, done, plan.bytes, i + 1, f.name)
                    if (pct != lastPct) {
                        lastPct = pct
                        nm.notify(7, notif("$pct% • ${Fs.humanSize(done)} de ${Fs.humanSize(plan.bytes)}", pct, true))
                    }
                }
                // Os dados do app vão junto, numa pasta à parte.
                AppBackup.writeInto(this, z, "_estante_app/")
            }
            val msg = "Concluído: ${plan.files.size} arquivos, ${Fs.humanSize(out.length())}\n${out.absolutePath.substringAfter("/0/")}"
            p.value = BackupProgress(false, done, plan.bytes, plan.files.size, "", msg)
            nm.notify(8, notif("Concluído • ${Fs.humanSize(out.length())}", null, false))
        } catch (e: Throwable) {
            out?.delete()
            val msg = if (e is InterruptedException) "Backup cancelado." else "Falhou: ${e.message}"
            p.value = BackupProgress(false, result = msg)
            nm.notify(8, notif(msg, null, false))
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }
}
