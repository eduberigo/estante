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
import java.io.File

class MainActivity : ComponentActivity() {
    /** Pedais de virar página (Bluetooth) chegam como teclas; o leitor se registra aqui. */
    var keyHandler: ((keyCode: Int, down: Boolean) -> Boolean)? = null

    private val hasPermission = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hasPermission.value = Environment.isExternalStorageManager()
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
        hasPermission.value = Environment.isExternalStorageManager()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
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

    var home: String?
        get() = sp.getString("home", null)
        set(v) { sp.edit().putString("home", v).apply() }

    var lastDir: String?
        get() = sp.getString("lastDir", null)
        set(v) { sp.edit().putString("lastDir", v).apply() }

    var grid: Boolean
        get() = sp.getBoolean("grid", true)
        set(v) { sp.edit().putBoolean("grid", v).apply() }
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
    val prefs = remember { Prefs(ctx) }
    val explorer = remember { ExplorerState(prefs) }
    var reading by remember { mutableStateOf<File?>(null) }

    val r = reading
    if (r != null) {
        BackHandler { reading = null }
        ReaderScreen(r, onClose = { reading = null })
    } else {
        ExplorerScreen(explorer, onOpen = { reading = it })
    }
}

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
