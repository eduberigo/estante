package com.eduberigo.estante

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Metrônomo simples. O tempo é contado em amostras de áudio (mesmo com o som desligado, toca silêncio),
 * então não "escorrega". Contagem: toca N compassos e para sozinho.
 */
class Metronome {
    @Volatile var bpm = 100
    @Volatile var beats = 4
    @Volatile var sound = true

    /** Tempo atual dentro do compasso (0 = primeiro). -1 = parado. */
    val beat = MutableStateFlow(-1)
    val running = MutableStateFlow(false)

    private val sr = 44100
    private var worker: Thread? = null
    @Volatile private var stopFlag = false

    private fun click(freq: Double, ms: Int, amp: Double): ShortArray {
        val n = sr * ms / 1000
        return ShortArray(n) { i ->
            val t = i.toDouble() / sr
            (sin(2 * PI * freq * t) * exp(-t * 60) * amp * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private val accent = click(1760.0, 35, 0.9)
    private val normal = click(1100.0, 30, 0.7)

    /** countInBars = null: contínuo; 2 = conta dois compassos e para. */
    fun start(countInBars: Int? = null) {
        stop()
        stopFlag = false
        running.value = true
        worker = Thread {
            val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sr)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            try {
                track.play()
                var i = 0
                val total = countInBars?.let { it * beats }
                while (!stopFlag && (total == null || i < total)) {
                    val b = beats.coerceAtLeast(1)
                    val pos = i % b
                    beat.value = pos
                    val len = (sr * 60.0 / bpm.coerceIn(20, 400)).toInt()
                    val buf = ShortArray(len)
                    if (sound) {
                        val c = if (pos == 0) accent else normal
                        System.arraycopy(c, 0, buf, 0, minOf(c.size, len))
                    }
                    var off = 0
                    while (off < len && !stopFlag) {
                        val w = track.write(buf, off, minOf(2048, len - off))
                        if (w <= 0) break
                        off += w
                    }
                    i++
                }
            } catch (_: Throwable) {
            } finally {
                try { track.stop() } catch (_: Throwable) {}
                track.release()
                beat.value = -1
                running.value = false
            }
        }.apply { name = "metronomo"; start() }
    }

    fun stop() {
        stopFlag = true
        worker?.join(300)
        worker = null
        beat.value = -1
        running.value = false
    }
}
