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
                val out = File(ctx.cacheDir, "teste-imagens.pdf")
                Export.imagesToPdf(imgs, out)
                Log.i(tag, "imagesToPdf ok bytes=${out.length()}")
                val s = Setlist("t", "Teste Export", (pdfs + imgs).map { SetItem(it.absolutePath) })
                val r = Export.binder(ctx, s)
                Log.i(tag, "binder ok ${r.files.map { it.absolutePath + " " + it.length() }} pulados=${r.skipped}")
                val r2 = Export.separate(ctx, s)
                Log.i(tag, "separados ok ${r2.files.size} pulados=${r2.skipped}")
                Log.i(tag, "txt ${Export.txt(s).length()}")
                Log.i(tag, "FIM OK")
            } catch (e: Throwable) {
                Log.e(tag, "FALHOU", e)
            }
        }
    }
}
