package com.eduberigo.estante

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import java.io.File

class MainActivity : ComponentActivity() {
    /** Pedais de virar página (Bluetooth) chegam como teclas; o leitor se registra aqui. */
    var keyHandler: ((keyCode: Int, down: Boolean) -> Boolean)? = null

    private val hasPermission = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)
        hasPermission.value = Environment.isExternalStorageManager()
        intent?.getStringExtra("selftest")?.let { SelfTest.run(this, it) }
        setContent {
            EstanteTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App(hasPermission.value, ::requestPermission)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppEvents.resumed.value = AppEvents.resumed.value + 1
        hasPermission.value = Environment.isExternalStorageManager()
    }

    /**
     * Pedal Bluetooth: depois de um toque na tela o Android fica em "modo toque", e a PRIMEIRA seta
     * que chega é usada pelo sistema para sair desse modo (o app nem recebe) — a não ser que uma
     * View simples (não um grupo) esteja com o foco. Por isso, com o leitor aberto, um receptor
     * invisível fica com o foco. Os toques na tela não são alterados.
     */
    private var keyCatcher: android.view.View? = null

    fun focusKeyCatcher(on: Boolean) {
        val content = findViewById<android.widget.FrameLayout>(android.R.id.content) ?: return
        val v = keyCatcher ?: android.view.View(this).also {
            it.isFocusable = true
            it.isFocusableInTouchMode = true
            it.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            it.defaultFocusHighlightEnabled = false
            content.addView(it, android.widget.FrameLayout.LayoutParams(1, 1))
            keyCatcher = it
        }
        if (on) v.requestFocus() else v.clearFocus()
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN || ev.actionMasked == android.view.MotionEvent.ACTION_UP) {
            android.util.Log.i("Estante", "toque ${if (ev.actionMasked == 0) "desce" else "sobe"} x=${ev.x.toInt()} foco=${currentFocus?.javaClass?.simpleName}")
        }
        val r = super.dispatchTouchEvent(ev)
        // Se um toque mudou o foco (ex.: campo de texto), devolve ao receptor quando o dedo sai.
        val a = ev.actionMasked
        if (keyHandler != null && a == android.view.MotionEvent.ACTION_UP) {
            val v = keyCatcher
            if (v != null && !v.isFocused && currentFocus !is android.widget.EditText) {
                window.decorView.post { if (keyHandler != null) v.requestFocus() }
            }
        }
        return r
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        KeyLog.add(event)
        val h = keyHandler
        if (h != null) {
            val down = event.action == KeyEvent.ACTION_DOWN
            if (h(event.keyCode, down)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun requestPermission() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("estante", Context.MODE_PRIVATE)

    private fun getList(k: String): List<String> =
        sp.getString(k, null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    private fun putList(k: String, v: List<String>) {
        sp.edit().putString(k, v.joinToString("\n")).apply()
    }

    /** Versão antiga tinha uma única pasta Início; mantida só para migrar. */
    var home: String?
        get() = sp.getString("home", null)
        set(v) { sp.edit().putString("home", v).apply() }

    var lastDir: String?
        get() = sp.getString("lastDir", null)
        set(v) { sp.edit().putString("lastDir", v).apply() }

    var grid: Boolean
        get() = sp.getBoolean("grid", true)
        set(v) { sp.edit().putBoolean("grid", v).apply() }

    /** 0 = grade de miniaturas, 1 = lista, 2 = lista lado a lado (colunas). */
    var viewMode: Int
        get() = sp.getInt("viewMode", if (grid) 0 else 1)
        set(v) { sp.edit().putInt("viewMode", v).apply() }

    /** Separar resultados em "memória interna" e "cartão". */
    /** Agrupar resultados pela pasta onde o arquivo está. */
    var groupByFolder: Boolean
        get() = sp.getBoolean("groupByFolder", false)
        set(v) { sp.edit().putBoolean("groupByFolder", v).apply() }

    var groupByVolume: Boolean
        get() = sp.getBoolean("groupByVolume", false)
        set(v) { sp.edit().putBoolean("groupByVolume", v).apply() }

    /** 0 pequenas, 1 médias, 2 grandes, 3 enormes. */
    var thumbSize: Int
        get() = sp.getInt("thumbSize", 1)
        set(v) { sp.edit().putInt("thumbSize", v).apply() }

    var enhance: Boolean
        get() = sp.getBoolean("enhance", false)
        set(v) { sp.edit().putBoolean("enhance", v).apply() }

    var textScale: Float
        get() = sp.getFloat("textScale", 1f)
        set(v) { sp.edit().putFloat("textScale", v).apply() }

    /** Leitor em tela cheia (esconde as barras do sistema). */
    var readerFullscreen: Boolean
        get() = sp.getBoolean("readerFullscreen", true)
        set(v) { sp.edit().putBoolean("readerFullscreen", v).apply() }

    var autoCrop: Boolean
        get() = sp.getBoolean("autoCrop", true)
        set(v) { sp.edit().putBoolean("autoCrop", v).apply() }

    /** null = automático (ligada no tablet, desligada no celular). */
    var showPreview: Boolean?
        get() = if (sp.contains("showPreview")) sp.getBoolean("showPreview", true) else null
        set(v) { if (v == null) sp.edit().remove("showPreview").apply() else sp.edit().putBoolean("showPreview", v).apply() }

    /** Fração da tela para a pré-visualização: em pé (em cima) e deitado (à direita). */
    var splitPortrait: Float
        get() = sp.getFloat("splitPortrait", 0.55f)
        set(v) { sp.edit().putFloat("splitPortrait", v).apply() }

    var splitLandscape: Float
        get() = sp.getFloat("splitLandscape", 0.45f)
        set(v) { sp.edit().putFloat("splitLandscape", v).apply() }

    /** Buscas recentes, da mais nova para a mais antiga. */
    var recentSearches: List<String>
        get() = getList("recentSearches")
        set(v) = putList("recentSearches", v)

    /** Pastas fixadas na tela Início, na ordem escolhida. */
    var pinned: List<String>
        get() = getList("pinned")
        set(v) = putList("pinned", v)

    /** Arquivos e pastas favoritos, do mais recente para o mais antigo. */
    var favorites: List<String>
        get() = getList("favorites")
        set(v) = putList("favorites", v)
}

@Composable
fun EstanteTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun App(hasPermission: Boolean, requestPermission: () -> Unit) {
    if (!hasPermission) {
        PermissionScreen(requestPermission)
        return
    }
    val ctx = LocalContext.current
    val activity = LocalContext.current as MainActivity
    val prefs = remember {
        // Teste automático: começa sempre das configurações padrão.
        if (activity.intent?.getBooleanExtra("limpar", false) == true) {
            ctx.getSharedPreferences("estante", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        }
        Prefs(ctx)
    }
    val explorer = remember {
        ExplorerState(prefs).also { st ->
            // Usados pelo teste automático: fixar pastas no Início e abrir uma pasta direto.
            activity.intent?.getStringExtra("fixar")?.split('|')?.forEach { if (it !in st.pinned) st.togglePin(it) }
            activity.intent?.getStringExtra("pasta")?.let { st.navigate(File(it)) }
        }
    }
    val lib = remember { Library(ctx).also { Ratings.init(ctx) } }
    var reading by remember {
        // Usado pelo teste automático: abrir direto um arquivo no leitor.
        mutableStateOf(activity.intent?.getStringExtra("abrir")?.let { Reading(listOf(File(it)), 0, null) })
    }

    val r = reading
    // A tela de arquivos continua montada por baixo do leitor: ao voltar, a lista está na mesma posição.
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        val d = androidx.compose.ui.platform.LocalDensity.current
        androidx.compose.foundation.layout.Box(
            Modifier
                .fillMaxSize()
                .then(if (r != null) Modifier.clearAndSetSemantics { } else Modifier)
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, d.fontScale * explorer.textScale),
            ) {
                ExplorerScreen(explorer, lib, onOpen = { files, i, title -> reading = Reading(files, i, title) })
            }
        }
        if (r != null) {
            BackHandler { reading = null }
            androidx.compose.foundation.layout.Box(
                Modifier
                    .fillMaxSize()
                    // Nenhum toque atravessa o leitor até a lista de baixo. Basta o leitor "receber" o toque
                    // (a lista fica por baixo e não é atingida). NÃO consumir aqui: consumir no passo final
                    // cancelava os botões do leitor sempre que o dedo mexia um tiquinho durante o toque.
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent(PointerEventPass.Final)
                        }
                    }
            ) {
                androidx.compose.runtime.key(r.files[r.index].absolutePath, r.index) {
                    ReaderScreen(
                        file = r.files[r.index],
                        setlistName = r.title,
                        position = if (r.files.size > 1) r.index to r.files.size else null,
                        startAtEnd = r.startAtEnd,
                        onNextSong = if (r.index < r.files.lastIndex) ({ reading = r.copy(index = r.index + 1, startAtEnd = false) }) else null,
                        onPrevSong = if (r.index > 0) ({ atEnd: Boolean -> reading = r.copy(index = r.index - 1, startAtEnd = atEnd) }) else null,
                        onPlayed = { f, secs -> lib.recordPlay(f.absolutePath, secs) },
                        lib = lib,
                        autoCrop = explorer.autoCrop,
                        onAutoCrop = { explorer.updateAutoCrop(it) },
                        onClose = { reading = null },
                    )
                }
            }
        }
    }
}

/** O que está aberto no leitor: um arquivo só ou um setlist inteiro. */
data class Reading(
    val files: List<File>,
    val index: Int,
    val title: String?,
    val startAtEnd: Boolean = false,
)

@Composable
fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Estante", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text(
            "Para mostrar suas pastas de partituras como elas estão no aparelho, " +
                "o app precisa da permissão \"Acesso a todos os arquivos\".\n\n" +
                "O app só lê seus arquivos. Nada é movido, renomeado ou apagado.",
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text("Dar permissão") }
    }
}

/** Eventos do app observados pelas telas (ex.: voltou ao app → atualizar o índice). */
object AppEvents {
    val resumed = kotlinx.coroutines.flow.MutableStateFlow(0)
}

/** Registro das últimas teclas recebidas (para testar o pedal Bluetooth). */
object KeyLog {
    val events = androidx.compose.runtime.mutableStateListOf<String>()
    private val fmt = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)

    fun add(e: KeyEvent) {
        if (e.action != KeyEvent.ACTION_DOWN) return
        val dev = e.device?.name ?: "?"
        events.add(0, "${fmt.format(java.util.Date())}  ${KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_")}" +
            (if (e.repeatCount > 0) " (repetição)" else "") + "  • $dev")
        while (events.size > 40) events.removeAt(events.lastIndex)
    }
}
