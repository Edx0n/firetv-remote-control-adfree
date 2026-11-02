package com.edx0n.firetvremote.firetv

import android.net.Uri
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class FireTvException(message: String, val code: Int = 0, cause: Throwable? = null) : IOException(message, cause) {
    val unauthorized: Boolean get() = code == 403
    val throttled: Boolean get() = code == 429
}

/** App instalado no Fire TV. [shortcutIntent] != null quando é um atalho (abre por intent). */
data class FireTvApp(val appId: String, val name: String, val iconUrl: String?, val shortcutIntent: String? = null)

/** O que a TV informa sobre si: versão da API ("turnstile") e recursos disponíveis. */
data class TvInfo(
    val turnstileVersion: String = "",
    val volumeSupported: Boolean? = null,
    val ringableRemotes: Int = 0,
) {
    /** TVs com turnstileVersion usam as rotas "v2" (/FireTV2, /FireTV/appsV2). */
    val v2: Boolean get() = turnstileVersion.isNotEmpty()
}

sealed interface PinResult {
    data class Paired(val token: String) : PinResult
    data object Wrong : PinResult
}

enum class KeyAction(val wire: String) { DOWN("keyDown"), UP("keyUp") }

/**
 * Cliente da API local de controle remoto do Fire TV ("Turnstile") — a mesma do app oficial
 * "Amazon Fire TV", reproduzida a partir da engenharia reversa descrita em docs/PROTOCOLO.md.
 *
 * - HTTPS na porta 8080; cabeçalhos `x-api-key` (fixo), `x-client-token` (do pareamento),
 *   `x-amzn-request-id` (UUID novo a cada requisição) e `Content-Type` JSON.
 * - Ocioso, o serviço para de responder: um POST DIAL em `/apps/FireTVRemote` (porta 8009)
 *   o reinicia em ~0,5 s. Isso é feito ao conectar e automaticamente quando algo falha.
 */
class FireTvClient(
    val host: String,
    @Volatile var token: String? = null,
    dialUrl: String? = null,
    apiPort: Int = 8080,
    private val http: OkHttpClient = Http.lan,
) {
    private val base = "https://${urlHost(host)}:$apiPort/v1"
    private val dial = dialUrl ?: "http://${urlHost(host)}:8009/apps/FireTVRemote"
    private val clients = ConcurrentHashMap<Long, OkHttpClient>()
    private val waking = Mutex()

    /** Cabeçalhos de toda chamada; o WebSocket de voz reaproveita os mesmos. */
    fun headers(auth: Boolean = true): Map<String, String> = buildMap {
        put("x-amzn-request-id", UUID.randomUUID().toString())
        put("Content-Type", JSON_TYPE)
        put("x-api-key", API_KEY)
        if (auth) token?.let { put("x-client-token", it) }
    }

    // ------------------------------------------------------------------ DIAL

    /** Pede ao Fire TV que inicie o serviço de controle (e acorde, se estiver dormindo). */
    suspend fun launchRemoteService(withApiKey: Boolean = false, timeoutMs: Long = 10_000): Boolean {
        val request = Request.Builder().url(dial)
            .header("x-amzn-request-id", UUID.randomUUID().toString())
            .apply { if (withApiKey) header("x-api-key", API_KEY) }
            .post(ByteArray(0).toRequestBody(TEXT))
            .build()
        return try {
            client(timeoutMs).newCall(request).await().use { it.code < 400 }
        } catch (_: IOException) {
            false
        }
    }

    /** Recuperação automática, como no app oficial: DIAL → 200 ms → status. */
    private suspend fun wakeUpTurnstile(): Boolean = waking.withLock {
        var launched = false
        for (attempt in 0 until 4) {
            if (launchRemoteService(withApiKey = true, timeoutMs = 2_000)) {
                launched = true
                break
            }
            delay(500)
        }
        if (!launched) return@withLock false
        delay(200)
        token == null || try {
            request("GET", "/FireTV/status", null, 2_000, recover = false)
            true
        } catch (_: FireTvException) {
            false
        }
    }

    // ------------------------------------------------------------------ pareamento

    /** Mostra o PIN de 4 dígitos na TV. */
    suspend fun displayPin(friendlyName: String) {
        val body = JSONObject().put("friendlyName", friendlyName).toString()
        retrying { request("POST", "/FireTV/pin/display", body, 3_000, auth = false, recover = false) }
    }

    /** Resposta 200 com `description` vazia = PIN errado; com conteúdo = token do cliente. */
    suspend fun verifyPin(pin: String): PinResult {
        val response = request("POST", "/FireTV/pin/verify", JSONObject().put("pin", pin).toString(), 10_000, auth = false, recover = false)
        val newToken = parseObject(response)?.text("description") ?: return PinResult.Wrong
        token = newToken
        return PinResult.Paired(newToken)
    }

    /** Fecha o PIN na tela da TV quando o usuário desiste (o app oficial envia o PIN "-1"). */
    suspend fun cancelPin() {
        try {
            request("POST", "/FireTV/pin/verify", """{"pin":"-1"}""", 3_000, auth = false, recover = false)
        } catch (_: FireTvException) {
        }
    }

    // ------------------------------------------------------------------ estado

    /** status + informações do aparelho (a segunda chamada é opcional: falhas não derrubam a conexão). */
    suspend fun info(): TvInfo {
        val status = retrying(attempts = 4) { request("GET", "/FireTV/status", null, 10_000) }
        val version = parseObject(status)?.text("turnstileVersion").orEmpty()
        val device = try {
            parseObject(request("GET", if (version.isNotEmpty()) "/FireTV2" else "/FireTV", null, 10_000, recover = false))
        } catch (e: FireTvException) {
            if (e.unauthorized) throw e
            null
        }
        return TvInfo(
            turnstileVersion = version,
            volumeSupported = device?.takeIf { it.has("isVolumeControlsSupported") }?.optBoolean("isVolumeControlsSupported"),
            ringableRemotes = device?.optInt("ringableRemoteCount") ?: 0,
        )
    }

    // ------------------------------------------------------------------ teclas e mídia

    /** Envia uma tecla. Sem [keyAction] é um clique; DOWN/UP seguram e soltam (a TV repete). */
    suspend fun send(remoteKey: RemoteKey, keyAction: KeyAction? = null) {
        if (remoteKey == RemoteKey.HOME && keyAction != KeyAction.UP) {
            // O app oficial acorda o aparelho antes de cada Início; erros aqui são ignorados.
            try {
                key("wakeup")
            } catch (_: FireTvException) {
            }
        }
        if (remoteKey.media) {
            media(remoteKey.action, remoteKey.scan, keyAction)
        } else {
            key(remoteKey.action, keyAction)
        }
    }

    suspend fun key(action: String, keyAction: KeyAction? = null) {
        val body = keyAction?.let { JSONObject().put("keyActionType", it.wire).toString() }
        request("POST", "/FireTV?action=$action", body, 3_000)
    }

    /** play (alterna play/pausa) ou scan; na mídia o "segurar" vai aninhado em `keyAction`. */
    suspend fun media(action: String, scanDirection: String? = null, keyAction: KeyAction? = null) {
        val body = JSONObject()
        if (scanDirection != null) {
            body.put("direction", scanDirection).put("durationInSeconds", "10").put("speed", "1")
        }
        if (keyAction != null) body.put("keyAction", JSONObject().put("keyActionType", keyAction.wire))
        request("POST", "/media?action=$action", if (body.length() == 0) null else body.toString(), 5_000)
    }

    // ------------------------------------------------------------------ apps

    suspend fun apps(v2: Boolean): List<FireTvApp> {
        val array = JSONArray(request("GET", if (v2) "/FireTV/appsV2" else "/FireTV/apps", null, 10_000))
        return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(::parseApp) }
    }

    suspend fun launch(app: FireTvApp) {
        val path = app.shortcutIntent?.let { "/FireTV/shortcut/" + Uri.encode(it) } ?: "/FireTV/app/${app.appId}"
        request("POST", path, null, 10_000)
    }

    suspend fun openSettings() {
        request("POST", "/FireTV/app/settings", null, 10_000)
    }

    // ------------------------------------------------------------------ teclado

    /** "visible"/"text": a TV aceita o texto inteiro de uma vez. Outro valor: letra por letra. */
    suspend fun keyboardState(): String = try {
        parseObject(request("GET", "/FireTV/keyboard", null, 5_000))?.text("state") ?: "hidden"
    } catch (e: FireTvException) {
        if (e.unauthorized) throw e
        "hidden"
    }

    suspend fun setKeyboardText(text: String) {
        request("POST", "/FireTV/keyboard", JSONObject().put("text", text).toString(), 5_000)
    }

    suspend fun typeCharacter(character: String) {
        request("POST", "/FireTV/text", JSONObject().put("text", character).toString(), 5_000)
    }

    // ------------------------------------------------------------------ extras

    suspend fun ringRemotes() {
        request("POST", "/FireTV/ringRemotes", null, 10_000)
    }

    suspend fun voiceCommand(start: Boolean) {
        request("POST", "/FireTV/voiceCommand?action=" + if (start) "start" else "stop", null, 10_000)
    }

    // ------------------------------------------------------------------ transporte

    private suspend fun request(
        method: String,
        path: String,
        body: String?,
        timeoutMs: Long,
        auth: Boolean = true,
        recover: Boolean = true,
    ): String {
        val builder = Request.Builder().url(base + path)
        headers(auth).forEach { (name, value) -> builder.header(name, value) }
        builder.method(method, if (method == "GET") null else (body ?: "").toRequestBody(JSON))
        try {
            client(timeoutMs).newCall(builder.build()).await().use { response ->
                val text = response.body?.string().orEmpty()
                // TVs antigas respondem 405 no status: o app oficial trata como sucesso ("v1").
                if (response.isSuccessful || (response.code == 405 && "status" in path)) return text
                val error = FireTvException(parseObject(text)?.text("description") ?: "HTTP ${response.code}", response.code)
                if (recover && response.code !in NO_RECOVERY) wakeUpTurnstile()
                throw error
            }
        } catch (e: FireTvException) {
            throw e
        } catch (e: IOException) {
            // Acorda o serviço; só repete se a requisição nem chegou à TV (sem risco de clique duplo).
            if (recover && wakeUpTurnstile() && e.neverReachedTv()) {
                return request(method, path, body, timeoutMs, auth, recover = false)
            }
            throw FireTvException("O Fire TV não respondeu", cause = e)
        }
    }

    private suspend fun <T> retrying(attempts: Int = 8, block: suspend () -> T): T {
        var wait = 500.0
        repeat(attempts - 1) {
            try {
                return block()
            } catch (e: FireTvException) {
                if (e.unauthorized || e.throttled) throw e
            }
            delay(wait.toLong())
            wait = minOf(wait * 1.1, 1000.0)
        }
        return block()
    }

    private fun client(timeoutMs: Long): OkHttpClient = clients.getOrPut(timeoutMs) {
        http.newBuilder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
    }

    private fun parseApp(o: JSONObject): FireTvApp? {
        val appId = o.text("appId") ?: return null
        val icon = o.text("tvIconArt")?.let(::sizedIcon) ?: o.text("iconArtSmallUri")
        val shortcut = if (o.optBoolean("isShortcutApp")) o.text("appShortcutLaunchIntent") ?: appId else null
        return FireTvApp(appId, o.text("name") ?: appId, icon, shortcut)
    }

    companion object {
        private const val API_KEY = "0987654321"
        private const val JSON_TYPE = "application/json; charset=utf-8"
        private val JSON = JSON_TYPE.toMediaType()
        private val TEXT = "text/plain".toMediaType()

        // Erros em que o app oficial não tenta acordar o serviço.
        private val NO_RECOVERY = setOf(403, 404, 405, 409, 429)

        /** Mesma regra do app oficial: pede a arte em 210 px inserindo `._SX210_` antes da extensão. */
        internal fun sizedIcon(url: String): String {
            val ext = Regex("""\.(jpe?g|png|gif)$""", RegexOption.IGNORE_CASE).find(url) ?: return url
            return url.substring(0, ext.range.first) + "._SX210_" + ext.value
        }
    }
}

private fun parseObject(text: String): JSONObject? = try {
    JSONObject(text)
} catch (_: Exception) {
    null
}

/** optString que trata ausente, vazio e JSON null como null. */
private fun JSONObject.text(name: String): String? =
    if (isNull(name)) null else optString(name).ifEmpty { null }

private fun IOException.neverReachedTv(): Boolean =
    this is ConnectException || this is NoRouteToHostException ||
        (this is SocketTimeoutException && message.orEmpty().contains("connect", ignoreCase = true))

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }

        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
    })
    continuation.invokeOnCancellation { runCatching { cancel() } }
}
