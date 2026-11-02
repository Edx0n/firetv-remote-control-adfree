package com.edx0n.firetvremote.firetv

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString

/**
 * Busca por voz (Alexa), no mesmo formato do app oficial:
 * `POST /v1/FireTV/voiceCommand?action=start` → WebSocket `wss://<ip>:9090/` com os mesmos
 * cabeçalhos → áudio PCM cru (16 kHz, mono, 16 bits) em frames binários → ao terminar, parar a
 * gravação, fechar o WebSocket e `POST ...voiceCommand?action=stop`.
 */
class VoiceSession(
    private val client: FireTvClient,
    private val scope: CoroutineScope,
    private val http: OkHttpClient = Http.lan,
) {
    private var job: Job? = null
    private var socket: WebSocket? = null

    @SuppressLint("MissingPermission") // a tela só chama start() depois de obter RECORD_AUDIO
    fun start(onError: (Throwable) -> Unit) {
        job = scope.launch(Dispatchers.IO) {
            var recorder: AudioRecord? = null
            try {
                client.voiceCommand(start = true)
                val opened = CompletableDeferred<WebSocket>()
                val request = Request.Builder().url("wss://${urlHost(client.host)}:$PORT/")
                    .apply { client.headers().forEach { (name, value) -> header(name, value) } }
                    .build()
                socket = http.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        opened.complete(webSocket)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        opened.completeExceptionally(t)
                    }
                })
                val ws = withTimeout(5_000) { opened.await() }

                val frame = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    frame * 10,
                )
                recorder.startRecording()
                val buffer = ByteArray(frame)
                // read() bloqueia até haver áudio: o envio sai no ritmo da fala, como a TV espera.
                while (isActive) {
                    val n = recorder.read(buffer, 0, buffer.size)
                    if (n > 0) ws.send(buffer.toByteString(0, n))
                }
            } catch (e: Exception) {
                if (isActive) onError(e)
            } finally {
                recorder?.runCatching {
                    stop()
                    release()
                }
            }
        }
    }

    suspend fun stop() {
        job?.cancelAndJoin()
        socket?.close(1000, "WebSocket closed")
        socket = null
        try {
            client.voiceCommand(start = false)
        } catch (_: FireTvException) {
        }
    }

    private companion object {
        const val PORT = 9090
        const val RATE = 16_000
    }
}
