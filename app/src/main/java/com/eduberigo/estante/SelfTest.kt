package com.eduberigo.estante

import android.content.Context
import android.util.Log
import kotlinx.coroutines.launch
import java.io.File

/** Teste automático (só roda quando o app é aberto com o extra "selftest"; usado no emulador). */
object SelfTest {
    fun run(ctx: Context, path: String) {
        AppScope.launch {
            val tag = "ESTANTE_SELFTEST"
            try {
                val dir = File(path)
                val files = dir.walkTopDown().filter { it.isFile }.toList()
                val pdfs = files.filter { Fs.kindOf(it) == Kind.PDF }
                val imgs = files.filter { Fs.kindOf(it) == Kind.IMAGE }
                Log.i(tag, "arquivos pdf=${pdfs.size} img=${imgs.size}")
                pdfs.firstOrNull()?.let { f ->
                    val d = openDoc(f)!!
                    val a = d.render(0, 800, 1200, false)
                    val b = d.render(0, 800, 1200, true)
                    Log.i(tag, "render sem recorte=${a?.width}x${a?.height} com recorte=${b?.width}x${b?.height}")
                    d.close()
                }
                imgs.firstOrNull()?.let { f ->
                    val d = openDoc(f)!!
                    Log.i(tag, "imagens em sequencia: paginas=${d.pageCount} inicio=${d.startPage}")
                }
                pdfs.firstOrNull()?.let { f ->
                    val d = openDoc(f)!!
                    val b = d.render(0, 800, 1200, true, true)
                    Log.i(tag, "legivel ${b?.width}x${b?.height}")
                    imgs.firstOrNull()?.let { im ->
                        val e2 = openDoc(im)!!.render(0, 800, 1200, true, true)
                        Log.i(tag, "legivel imagem ${e2?.width}x${e2?.height}")
                    }
                    d.close()
                }
                imgs.filter { it.name.startsWith("torta") }.forEach { f ->
                    val bmp = decodeImage(f, 1600, 2400)
                    val a = Enhance.skewAngle(bmp)
                    val fixed = Enhance.rotate(bmp, a)
                    Log.i(tag, "torta ${f.name}: angulo=$a depois=${Enhance.skewAngle(fixed)}")
                }
                pdfs.firstOrNull()?.let { f ->
                    val o = File(ctx.cacheDir, "legivel.pdf")
                    Export.enhanceFile(f, o, true) { _, _ -> }
                    Log.i(tag, "arquivo legivel pdf bytes=${o.length()}")
                }
                imgs.firstOrNull { it.name.startsWith("torta") }?.let { f ->
                    val o = File(ctx.cacheDir, "legivel.jpg")
                    Export.enhanceFile(f, o, true) { _, _ -> }
                    Log.i(tag, "arquivo legivel img bytes=${o.length()}")
                }
                val out = File(ctx.cacheDir, "teste-imagens.pdf")
                Export.imagesToPdf(imgs, out)
                Log.i(tag, "imagesToPdf ok bytes=${out.length()}")
                val s = Setlist("t", "Teste Export", (pdfs + imgs).map { SetItem(it.absolutePath) })
                val r = Export.binder(ctx, s)
                Log.i(tag, "binder ok ${r.files.map { it.absolutePath + " " + it.length() }} pulados=${r.skipped}")
                val r2 = Export.separate(ctx, s)
                Log.i(tag, "separados ok ${r2.files.size} pulados=${r2.skipped}")
                Log.i(tag, "txt ${Export.txt(s).length()}")
                pdfs.firstOrNull()?.let { f ->
                    val n = FileNotes()
                        .add(0, Ann("pen", -65536, 0.0035f, listOf(0.1f, 0.1f, 0.2f, 0.2f)))
                        .add(1, Ann("finger", -16777216, 0.03f, listOf(0.5f, 0.5f), "3"))
                        .copy(anchors = listOf(Anchor("Coda", 1, 0.1f, 0.6f)), jumps = listOf(Jump("Coda", 0, 0.9f, 0.9f)))
                    AnnStore.save(ctx, f.absolutePath, n)
                    val back = AnnStore.load(ctx, f.absolutePath)
                    Log.i(tag, "anotacoes ok=${back == n} paginas=${back.pages.keys} ancoras=${back.anchors.size}")
                }
                val ab = AppBackup.create(ctx)
                Log.i(tag, "backup app ${ab.name} bytes=${ab.length()} restaurados=${AppBackup.restore(ctx, ab)}")
                val plan = LibraryBackup.plan(listOf(dir))
                Log.i(tag, "plano backup ${plan.files.size} arquivos ${plan.bytes} bytes")
                LibraryBackup.start(ctx, listOf(dir.absolutePath), File(ctx.getExternalFilesDir(null), "bk").absolutePath)
                var w = 0
                while (w < 40 && (LibraryBackup.progress.value.running || LibraryBackup.progress.value.result == null)) {
                    kotlinx.coroutines.delay(250); w++
                }
                Log.i(tag, "backup partituras: ${LibraryBackup.progress.value.result}")
                Log.i(tag, "FIM OK")
            } catch (e: Throwable) {
                Log.e(tag, "FALHOU", e)
            }
        }
    }
}
