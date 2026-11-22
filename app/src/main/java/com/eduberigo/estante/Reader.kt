package com.eduberigo.estante

import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

private val nextKeys = setOf(
    KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_NEXT,
)
private val prevKeys = setOf(
    KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_MEDIA_PREVIOUS,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReaderScreen(
    file: File,
    setlistName: String?,
    position: Pair<Int, Int>?,
    startAtEnd: Boolean,
    onNextSong: (() -> Unit)?,
    onPrevSong: ((atEnd: Boolean) -> Unit)?,
    onPlayed: (File, Int) -> Unit,
    autoCrop: Boolean,
    onAutoCrop: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val activity = LocalContext.current as MainActivity
    val nextSong by rememberUpdatedState(onNextSong)
    val prevSong by rememberUpdatedState(onPrevSong)
    val played by rememberUpdatedState(onPlayed)

    // Conta como "tocada" se a partitura ficou aberta pelo menos 30 segundos.
    DisposableEffect(file) {
        val start = System.currentTimeMillis()
        onDispose {
            val secs = ((System.currentTimeMillis() - start) / 1000).toInt()
            if (secs >= 30) played(file, secs)
        }
    }
    val doc = remember(file) { openDoc(file) }
    DisposableEffect(doc) { onDispose { doc?.close() } }

    // Tela cheia e tela sempre ligada enquanto lê.
    DisposableEffect(Unit) {
        val window = activity.window
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val ctrl = WindowCompat.getInsetsController(window, window.decorView)
        ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctrl.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            ctrl.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    if (doc == null) {
        Column(
            Modifier.fillMaxSize().background(Color.Black).padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Não foi possível abrir este arquivo.", color = Color.White)
            Text(file.name, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onClose) { Text("Voltar") }
        }
        return
    }

    val count = doc.pageCount.coerceAtLeast(1)
    val pager = rememberPagerState(initialPage = if (startAtEnd) count - 1 else 0, pageCount = { count })
    val scope = rememberCoroutineScope()
    var showBars by remember { mutableStateOf(false) }

    val turn: (Int) -> Unit = remember(pager, count) {
        { delta ->
            val raw = pager.currentPage + delta
            when {
                raw > count - 1 && nextSong != null -> nextSong?.invoke()
                raw < 0 && prevSong != null -> prevSong?.invoke(true)
                else -> {
                    val target = raw.coerceIn(0, count - 1)
                    if (target != pager.currentPage) scope.launch { pager.scrollToPage(target) }
                }
            }
        }
    }

    DisposableEffect(turn) {
        activity.keyHandler = { code, down ->
            when (code) {
                in nextKeys -> { if (down) turn(1); true }
                in prevKeys -> { if (down) turn(-1); true }
                else -> false
            }
        }
        onDispose { activity.keyHandler = null }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            key = { it },
        ) { index ->
            PageView(doc, index, autoCrop) { frac ->
                when {
                    frac < 0.3f -> turn(-1)
                    frac > 0.7f -> turn(1)
                    else -> showBars = !showBars
                }
            }
        }

        val clock by produceState(nowText()) {
            while (true) {
                value = nowText()
                kotlinx.coroutines.delay(1000L * (60 - java.util.Calendar.getInstance().get(java.util.Calendar.SECOND)).coerceAtLeast(1))
            }
        }
        if (!showBars) {
            // Relógio discreto, sempre visível.
            Text(
                if (position != null) "${position.first + 1}/${position.second} • $clock" else clock,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .displayCutoutPadding()
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.45f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }

        if (showBars) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Color.Black.copy(alpha = 0.75f))
                    .statusBarsPadding()
                    .displayCutoutPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Fechar", tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        file.nameWithoutExtension,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (position != null) {
                        Text(
                            "${setlistName ?: "Setlist"} • música ${position.first + 1} de ${position.second}",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                }
                if (position != null) {
                    IconButton(enabled = onPrevSong != null, onClick = { onPrevSong?.invoke(false) }) {
                        Text("⏮", color = if (onPrevSong != null) Color.White else Color.DarkGray, style = MaterialTheme.typography.titleLarge)
                    }
                    IconButton(enabled = onNextSong != null, onClick = { onNextSong?.invoke() }) {
                        Text("⏭", color = if (onNextSong != null) Color.White else Color.DarkGray, style = MaterialTheme.typography.titleLarge)
                    }
                }
                Text(clock, color = Color.White, modifier = Modifier.padding(horizontal = 8.dp))
                Text(
                    "${pager.currentPage + 1} / $count",
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                IconButton(onClick = { onAutoCrop(!autoCrop) }) {
                    Icon(
                        painterResource(R.drawable.ic_crop),
                        if (autoCrop) "Desligar recorte automático" else "Ligar recorte automático",
                        tint = if (autoCrop) Color(0xFF8AB4F8) else Color.Gray,
                    )
                }
            }
            if (count > 1) {
                var pos by remember(pager.currentPage) { mutableFloatStateOf(pager.currentPage.toFloat()) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(Color.Black.copy(alpha = 0.75f))
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                ) {
                    Slider(
                        value = pos,
                        onValueChange = { pos = it },
                        onValueChangeFinished = {
                            val t = pos.roundToInt().coerceIn(0, count - 1)
                            scope.launch { pager.scrollToPage(t) }
                        },
                        valueRange = 0f..(count - 1).toFloat(),
                    )
                }
            }
        }
    }
}

/** Uma página: desenhada no tamanho da tela; pinça para ampliar; toque nas bordas vira. */
@Composable
private fun PageView(doc: Doc, index: Int, crop: Boolean, onTap: (Float) -> Unit) {
    val tapHandler by rememberUpdatedState(onTap)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wPx = constraints.maxWidth
        val hPx = constraints.maxHeight
        val bmp by produceState<Bitmap?>(null, doc, index, wPx, hPx, crop) {
            // Um pouco acima do tamanho da tela, para o zoom não borrar tanto.
            value = doc.render(index, (wPx * 1.3f).toInt(), (hPx * 1.3f).toInt(), crop)
        }
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { p -> tapHandler(p.x / size.width) })
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val fingers = event.changes.count { it.pressed }
                            if (fingers >= 2 || scale > 1.01f) {
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val centroid = event.calculateCentroid(useCurrent = true)
                                var newScale = (scale * zoom).coerceIn(1f, 5f)
                                val center = Offset(size.width / 2f, size.height / 2f)
                                var t = offset
                                if (centroid != Offset.Unspecified) {
                                    val g = centroid - center
                                    t = g - (g - t) * (newScale / scale)
                                }
                                t += pan
                                if (newScale < 1.02f) {
                                    newScale = 1f
                                    t = Offset.Zero
                                }
                                val maxX = (newScale - 1f) * size.width / 2f
                                val maxY = (newScale - 1f) * size.height / 2f
                                offset = Offset(t.x.coerceIn(-maxX, maxX), t.y.coerceIn(-maxY, maxY))
                                scale = newScale
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val b = bmp
            if (b != null) {
                Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
            } else {
                CircularProgressIndicator(color = Color.DarkGray)
            }
        }
    }
}

private val clockFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale("pt", "BR"))
private fun nowText(): String = clockFmt.format(java.util.Date())
