package com.edx0n.firetvremote.ui

import android.app.Application
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.edx0n.firetvremote.firetv.DeviceStore
import com.edx0n.firetvremote.firetv.DiscoveredTv
import com.edx0n.firetvremote.firetv.FireTvApp
import com.edx0n.firetvremote.firetv.FireTvClient
import com.edx0n.firetvremote.firetv.FireTvDevice
import com.edx0n.firetvremote.firetv.FireTvDiscovery
import com.edx0n.firetvremote.firetv.FireTvException
import com.edx0n.firetvremote.firetv.KeyAction
import com.edx0n.firetvremote.firetv.PinResult
import com.edx0n.firetvremote.firetv.Press
import com.edx0n.firetvremote.firetv.RemoteKey
import com.edx0n.firetvremote.firetv.TvInfo
import com.edx0n.firetvremote.firetv.VoiceSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

data class PairingUi(val target: FireTvDevice, val phase: Phase, val error: String? = null) {
    enum class Phase { REQUESTING, ENTER_PIN, VERIFYING }
}

enum class Conn { CONNECTING, CONNECTED, OFFLINE }

/** MIRROR: a TV recebe o texto inteiro a cada mudança. LETTERS: letra por letra (TVs antigas). */
enum class KeyboardMode { CHECKING, MIRROR, LETTERS }

data class UiState(
    val devices: List<FireTvDevice> = emptyList(),
    val discovered: List<DiscoveredTv> = emptyList(),
    val scanning: Boolean = false,
    val current: FireTvDevice? = null,
    val showDevices: Boolean = false,
    val conn: Conn = Conn.CONNECTING,
    val info: TvInfo? = null,
    val pairing: PairingUi? = null,
    val apps: List<FireTvApp> = emptyList(),
    val appsLoading: Boolean = false,
    val keyboardMode: KeyboardMode = KeyboardMode.CHECKING,
    val touchpad: Boolean = false,
    val volumeKeys: Boolean = true,
    val listening: Boolean = false,
    val message: String? = null,
)

/** Tudo o que as telas podem pedir. Separado do ViewModel para as telas renderizarem em testes. */
interface RemoteActions {
    fun openDevices()
    fun closeDevices()
    fun discover()
    fun addManual(ip: String)
    fun select(device: FireTvDevice)
    fun forget(device: FireTvDevice)
    fun startPairing(device: FireTvDevice)
    fun retryPairing()
    fun submitPin(pin: String)
    fun cancelPairing()
    fun reconnect()
    fun press(key: RemoteKey)
    fun keyDown(key: RemoteKey)
    fun keyUp(key: RemoteKey)
    fun directionDown(direction: Direction)
    fun directionUp(direction: Direction)
    fun step(direction: Direction)
    fun openKeyboard()
    fun keyboardInput(text: String)
    fun loadApps()
    fun launch(app: FireTvApp)
    fun openFireTvSettings()
    fun ringRemotes()
    fun voiceDown()
    fun voiceUp()
    fun setTouchpad(enabled: Boolean)
    fun setVolumeKeys(enabled: Boolean)
    fun messageShown()
}

class RemoteViewModel(application: Application) : AndroidViewModel(application), RemoteActions {
    private val store = DeviceStore(application)
    private val _state: MutableStateFlow<UiState>
    val state: StateFlow<UiState>

    private var client: FireTvClient? = null
    private var lastConnectCheck = 0L

    // Fila única, como no app oficial: keyDown/keyUp e letras chegam na ordem em que foram tocados.
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val pending = AtomicInteger(0)
    private val longPressJobs = mutableMapOf<RemoteKey, Job>()
    private val longPressing = mutableSetOf<RemoteKey>()
    private var discoverJob: Job? = null
    private var pairingJob: Job? = null
    private var lastHardwareRepeat = 0L

    // Teclado: o que foi digitado no celular e o que a TV já tem.
    private var typed = ""
    private var tvText = ""
    private var keyboardSyncQueued = false

    private var voice: VoiceSession? = null
    private var voiceStartedAt = 0L
    private var voiceTimeout: Job? = null

    /** [droppable]: pode ser descartado se ficou velho na fila. keyUp e texto nunca são. */
    private class Command(val createdAt: Long, val droppable: Boolean, val block: suspend (FireTvClient) -> Unit)

    init {
        val devices = store.load()
        val current = devices.firstOrNull { it.id == store.currentId && it.token != null }
            ?: devices.firstOrNull { it.token != null }
        _state = MutableStateFlow(
            UiState(devices = devices, current = current, touchpad = store.touchpad, volumeKeys = store.volumeKeys),
        )
        state = _state.asStateFlow()
        viewModelScope.launch { for (command in commands) execute(command) }
        current?.let(::useDevice)
    }

    // ---------------------------------------------------------------- dispositivos

    override fun openDevices() = _state.update { it.copy(showDevices = true) }

    override fun closeDevices() = _state.update { it.copy(showDevices = false) }

    override fun discover() {
        if (discoverJob?.isActive == true) return
        discoverJob = viewModelScope.launch {
            _state.update { it.copy(scanning = true, discovered = emptyList()) }
            try {
                FireTvDiscovery.discover(getApplication()) { tv ->
                    _state.update { s -> s.copy(discovered = s.discovered.filterNot { it.host == tv.host } + tv) }
                    viewModelScope.launch { followMovedDevice(tv) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                _state.update { it.copy(scanning = false) }
            }
        }
    }

    /** Se o roteador deu outro IP para um Fire TV já pareado, reconhece pelo UDN e atualiza. */
    private fun followMovedDevice(tv: DiscoveredTv) {
        val known = _state.value.devices.firstOrNull { it.udn != null && it.udn == tv.udn } ?: return
        if (known.host == tv.host && known.dialUrl == tv.dialUrl) return
        val moved = known.copy(host = tv.host, dialUrl = tv.dialUrl ?: known.dialUrl)
        val devices = store.upsert(moved)
        val isCurrent = _state.value.current?.id == moved.id
        _state.update { it.copy(devices = devices, current = if (isCurrent) moved else it.current) }
        if (isCurrent) useDevice(moved)
    }

    override fun addManual(ip: String) {
        viewModelScope.launch {
            _state.update { it.copy(scanning = true) }
            val tv = FireTvDiscovery.probe(ip) ?: DiscoveredTv(ip, "Fire TV ($ip)", null, null)
            _state.update { s -> s.copy(scanning = false, discovered = s.discovered.filterNot { it.host == tv.host } + tv) }
            val known = _state.value.devices.firstOrNull { it.host == ip && it.token != null }
            if (known != null) select(known) else startPairing(FireTvDevice.from(tv))
        }
    }

    override fun select(device: FireTvDevice) {
        if (device.token == null) {
            startPairing(device)
            return
        }
        store.currentId = device.id
        _state.update { it.copy(current = device, showDevices = false, apps = emptyList(), info = null) }
        useDevice(device)
    }

    override fun forget(device: FireTvDevice) {
        val devices = store.remove(device)
        val wasCurrent = _state.value.current?.id == device.id
        if (wasCurrent) {
            client = null
            store.currentId = null
        }
        _state.update { it.copy(devices = devices, current = if (wasCurrent) null else it.current) }
    }

    // ---------------------------------------------------------------- pareamento

    override fun startPairing(device: FireTvDevice) {
        pairingJob?.cancel()
        _state.update { it.copy(pairing = PairingUi(device, PairingUi.Phase.REQUESTING)) }
        pairingJob = viewModelScope.launch { requestPin(device, error = null) }
    }

    /** DIAL → 500 ms → pin/display, igual ao app oficial. */
    private suspend fun requestPin(device: FireTvDevice, error: String?) {
        val tv = FireTvClient(device.host, dialUrl = device.dialUrl)
        try {
            tv.launchRemoteService()
            delay(500)
            tv.displayPin(friendlyName())
            _state.update { it.copy(pairing = it.pairing?.copy(phase = PairingUi.Phase.ENTER_PIN, error = error)) }
        } catch (e: FireTvException) {
            val message = if (e.throttled) {
                "Muitas tentativas. Espere um pouco e tente de novo."
            } else {
                "Não consegui falar com a TV (${e.message}). Ela está ligada e no mesmo Wi-Fi?"
            }
            _state.update { it.copy(pairing = it.pairing?.copy(phase = PairingUi.Phase.ENTER_PIN, error = message)) }
        }
    }

    override fun retryPairing() {
        _state.value.pairing?.target?.let(::startPairing)
    }

    override fun submitPin(pin: String) {
        val pairing = _state.value.pairing ?: return
        if (pin.length != 4 || pairing.phase == PairingUi.Phase.VERIFYING) return
        pairingJob?.cancel()
        _state.update { it.copy(pairing = pairing.copy(phase = PairingUi.Phase.VERIFYING, error = null)) }
        pairingJob = viewModelScope.launch {
            val tv = FireTvClient(pairing.target.host, dialUrl = pairing.target.dialUrl)
            var attempt = 0
            while (true) {
                try {
                    when (val result = tv.verifyPin(pin)) {
                        is PinResult.Paired -> {
                            val device = pairing.target.copy(token = result.token)
                            val devices = store.upsert(device)
                            _state.update { it.copy(devices = devices, pairing = null) }
                            select(device)
                            toast("Pareado com ${device.name}")
                        }
                        // PIN errado: a TV já descartou esse PIN; pede um novo, como o app oficial.
                        PinResult.Wrong -> requestPin(pairing.target, "PIN incorreto. Veja o novo PIN na TV.")
                    }
                    return@launch
                } catch (e: FireTvException) {
                    if (e.throttled) {
                        _state.update { it.copy(pairing = pairing.copy(phase = PairingUi.Phase.ENTER_PIN, error = "Muitas tentativas. Espere um pouco.")) }
                        return@launch
                    }
                    if (++attempt > 2) {
                        _state.update { it.copy(pairing = pairing.copy(phase = PairingUi.Phase.ENTER_PIN, error = "Falha de conexão ao confirmar o PIN.")) }
                        return@launch
                    }
                    tv.launchRemoteService()
                }
            }
        }
    }

    override fun cancelPairing() {
        val pairing = _state.value.pairing ?: return
        pairingJob?.cancel()
        _state.update { it.copy(pairing = null) }
        // Tira o PIN da tela da TV.
        viewModelScope.launch { FireTvClient(pairing.target.host, dialUrl = pairing.target.dialUrl).cancelPin() }
    }

    private fun friendlyName() = "Controle Fire TV (${Build.MODEL})"

    // ---------------------------------------------------------------- conexão

    private fun useDevice(device: FireTvDevice) {
        val tv = FireTvClient(device.host, device.token, device.dialUrl)
        client = tv
        _state.update { it.copy(conn = Conn.CONNECTING) }
        viewModelScope.launch { checkConnection(tv) }
    }

    fun onForeground() {
        val tv = client ?: return
        if (SystemClock.elapsedRealtime() - lastConnectCheck > 20_000) viewModelScope.launch { checkConnection(tv) }
    }

    override fun reconnect() {
        val tv = client ?: return
        _state.update { it.copy(conn = Conn.CONNECTING) }
        viewModelScope.launch { checkConnection(tv, rediscover = true) }
    }

    /** Sequência do app oficial ao conectar: DIAL → status → informações do aparelho. */
    private suspend fun checkConnection(tv: FireTvClient, rediscover: Boolean = false) {
        lastConnectCheck = SystemClock.elapsedRealtime()
        try {
            tv.launchRemoteService()
            val info = tv.info()
            if (client === tv) _state.update { it.copy(conn = Conn.CONNECTED, info = info) }
        } catch (e: FireTvException) {
            if (client !== tv) return
            if (e.unauthorized) {
                onUnauthorized()
            } else {
                _state.update { it.copy(conn = Conn.OFFLINE) }
                if (rediscover) discover()
            }
        }
    }

    private fun onUnauthorized() {
        val device = _state.value.current ?: return
        val unpaired = device.copy(token = null)
        val devices = store.upsert(unpaired)
        client = null
        _state.update { it.copy(devices = devices, current = null, conn = Conn.OFFLINE, message = "A TV pediu para parear de novo.") }
        startPairing(unpaired)
    }

    // ---------------------------------------------------------------- fila de comandos

    private fun enqueue(droppable: Boolean = true, block: suspend (FireTvClient) -> Unit) {
        pending.incrementAndGet()
        commands.trySend(Command(SystemClock.elapsedRealtime(), droppable, block))
    }

    private suspend fun execute(command: Command) {
        try {
            val tv = client ?: return
            // Clique que esperou demais (TV acordando, Wi-Fi ruim) é descartado para não chegar atrasado.
            if (command.droppable && SystemClock.elapsedRealtime() - command.createdAt > STALE_MS) return
            command.block(tv)
            if (_state.value.conn != Conn.CONNECTED) _state.update { it.copy(conn = Conn.CONNECTED) }
        } catch (e: FireTvException) {
            if (e.unauthorized) onUnauthorized() else _state.update { it.copy(conn = Conn.OFFLINE) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _state.update { it.copy(conn = Conn.OFFLINE) }
        } finally {
            pending.decrementAndGet()
        }
    }

    // ---------------------------------------------------------------- teclas

    /** Clique "programático" (touchpad, acessibilidade): direcional = keyDown e keyUp 200 ms depois. */
    override fun press(key: RemoteKey) {
        if (key.press == Press.DOWN_UP) {
            enqueue { tv ->
                tv.send(key, KeyAction.DOWN)
                delay(TAP_MS)
                tv.send(key, KeyAction.UP)
            }
        } else {
            enqueue { it.send(key) }
        }
    }

    override fun keyDown(key: RemoteKey) {
        when (key.press) {
            Press.DOWN_UP -> enqueue { it.send(key, KeyAction.DOWN) }
            Press.TAP -> enqueue { it.send(key) }
            Press.LONG_PRESS -> {
                longPressJobs.remove(key)?.cancel()
                longPressJobs[key] = viewModelScope.launch {
                    delay(LONG_PRESS_MS)
                    longPressing += key
                    enqueue(droppable = false) { it.send(key, KeyAction.DOWN) }
                }
            }
        }
    }

    override fun keyUp(key: RemoteKey) {
        when (key.press) {
            Press.DOWN_UP -> enqueue(droppable = false) { it.send(key, KeyAction.UP) }
            Press.TAP -> Unit
            Press.LONG_PRESS -> {
                longPressJobs.remove(key)?.cancel()
                if (longPressing.remove(key)) {
                    enqueue(droppable = false) { it.send(key, KeyAction.UP) }
                } else {
                    enqueue { it.send(key) }
                }
            }
        }
    }

    override fun directionDown(direction: Direction) = keyDown(direction.toKey())

    override fun directionUp(direction: Direction) = keyUp(direction.toKey())

    override fun step(direction: Direction) = press(direction.toKey())

    private fun Direction.toKey() = when (this) {
        Direction.UP -> RemoteKey.UP
        Direction.DOWN -> RemoteKey.DOWN
        Direction.LEFT -> RemoteKey.LEFT
        Direction.RIGHT -> RemoteKey.RIGHT
        Direction.CENTER -> RemoteKey.SELECT
    }

    /** Botões físicos de volume do celular → volume da TV (um clique por evento, como o app oficial). */
    fun onHardwareKey(keyCode: Int, down: Boolean, repeat: Int): Boolean {
        val s = _state.value
        if (!s.volumeKeys || s.current == null || s.showDevices || s.pairing != null) return false
        val key = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> RemoteKey.VOLUME_UP
            KeyEvent.KEYCODE_VOLUME_DOWN -> RemoteKey.VOLUME_DOWN
            else -> return false
        }
        if (down) {
            val now = SystemClock.elapsedRealtime()
            if (repeat == 0 || (now - lastHardwareRepeat >= HARDWARE_REPEAT_MS && pending.get() < 2)) {
                lastHardwareRepeat = now
                enqueue { it.send(key) }
            }
        }
        return true
    }

    // ---------------------------------------------------------------- teclado

    override fun openKeyboard() {
        typed = ""
        tvText = ""
        _state.update { it.copy(keyboardMode = KeyboardMode.CHECKING) }
        enqueue(droppable = false) { tv ->
            val mode = if (tv.keyboardState() in setOf("visible", "text")) KeyboardMode.MIRROR else KeyboardMode.LETTERS
            _state.update { it.copy(keyboardMode = mode) }
        }
    }

    /** Recebe o texto inteiro do campo do celular; envios rápidos são agrupados em um só. */
    override fun keyboardInput(text: String) {
        typed = text
        if (keyboardSyncQueued) return
        keyboardSyncQueued = true
        enqueue(droppable = false) { tv ->
            keyboardSyncQueued = false
            val target = typed
            if (_state.value.keyboardMode == KeyboardMode.MIRROR) {
                tv.setKeyboardText(target)
            } else {
                // Modo letra por letra: apaga até o prefixo comum e digita o resto.
                val common = tvText.commonPrefixWith(target).length
                repeat(tvText.length - common) { tv.key(RemoteKey.BACKSPACE.action) }
                var i = common
                while (i < target.length) {
                    val next = target.offsetByCodePoints(i, 1)
                    tv.typeCharacter(target.substring(i, next))
                    i = next
                }
            }
            tvText = target
        }
    }

    // ---------------------------------------------------------------- apps

    override fun loadApps() {
        val tv = client ?: return
        if (_state.value.appsLoading) return
        _state.update { it.copy(appsLoading = true) }
        viewModelScope.launch {
            try {
                val v2 = _state.value.info?.v2 ?: tv.info().v2
                val apps = tv.apps(v2)
                _state.update { it.copy(apps = apps, appsLoading = false) }
            } catch (e: FireTvException) {
                _state.update { it.copy(appsLoading = false) }
                if (e.unauthorized) onUnauthorized() else toast("Não consegui listar os apps (${e.message}).")
            }
        }
    }

    override fun launch(app: FireTvApp) = enqueue { it.launch(app) }

    override fun openFireTvSettings() = enqueue { it.openSettings() }

    override fun ringRemotes() {
        val tv = client ?: return
        viewModelScope.launch {
            try {
                tv.ringRemotes()
                toast("Fazendo o controle remoto tocar…")
            } catch (_: FireTvException) {
                toast("Não foi possível tocar o controle.")
            }
        }
    }

    // ---------------------------------------------------------------- voz

    /** Toque liga/desliga; segurar e soltar também encerra (aperte e fale). */
    override fun voiceDown() {
        if (voice != null) {
            voiceStop()
            return
        }
        val tv = client ?: return
        voiceStartedAt = SystemClock.elapsedRealtime()
        _state.update { it.copy(listening = true) }
        voice = VoiceSession(tv, viewModelScope).also { session ->
            session.start { error ->
                viewModelScope.launch {
                    toast("Alexa indisponível: ${error.message ?: error.javaClass.simpleName}")
                    voiceStop()
                }
            }
        }
        voiceTimeout = viewModelScope.launch {
            delay(VOICE_MAX_MS)
            voiceStop()
        }
    }

    override fun voiceUp() {
        if (voice != null && SystemClock.elapsedRealtime() - voiceStartedAt > PUSH_TO_TALK_MS) voiceStop()
    }

    private fun voiceStop() {
        val session = voice ?: return
        voice = null
        voiceTimeout?.cancel()
        _state.update { it.copy(listening = false) }
        viewModelScope.launch { session.stop() }
    }

    // ---------------------------------------------------------------- preferências

    override fun setTouchpad(enabled: Boolean) {
        store.touchpad = enabled
        _state.update { it.copy(touchpad = enabled) }
    }

    override fun setVolumeKeys(enabled: Boolean) {
        store.volumeKeys = enabled
        _state.update { it.copy(volumeKeys = enabled) }
    }

    override fun messageShown() = _state.update { it.copy(message = null) }

    private fun toast(message: String) = _state.update { it.copy(message = message) }

    private companion object {
        const val STALE_MS = 2500L
        const val TAP_MS = 200L
        const val LONG_PRESS_MS = 300L
        const val HARDWARE_REPEAT_MS = 150L
        const val PUSH_TO_TALK_MS = 600L
        const val VOICE_MAX_MS = 15_000L
    }
}
