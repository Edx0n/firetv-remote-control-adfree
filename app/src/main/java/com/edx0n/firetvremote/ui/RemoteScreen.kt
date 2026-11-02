package com.edx0n.firetvremote.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.WifiFind
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.edx0n.firetvremote.firetv.RemoteKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(state: UiState, vm: RemoteActions) {
    val device = state.current ?: return
    var appsOpen by rememberSaveable { mutableStateOf(false) }
    var keyboardOpen by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            vm.messageShown()
            snackbar.showSnackbar(it)
        }
    }

    val context = LocalContext.current
    var micGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { RemoteTopBar(state, vm) },
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            val compact = maxHeight < 640.dp
            val button: Dp = if (compact) 52.dp else 64.dp
            val small: Dp = if (compact) 46.dp else 54.dp
            val padSize = minOf(maxWidth * 0.86f, maxHeight * (if (compact) 0.40f else 0.44f))
            fun label(text: String) = if (compact) null else text

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxSize(),
            ) {
                ButtonRow {
                    KeyButton(vm, RemoteKey.SLEEP, Icons.Rounded.PowerSettingsNew, "Dormir", small, compact)
                    RemoteButton(
                        Icons.Rounded.Apps, "Apps",
                        onDown = {
                            appsOpen = true
                            vm.loadApps()
                        },
                        onUp = {}, size = small, label = label("Apps"),
                    )
                    RemoteButton(
                        Icons.Rounded.Mic, "Alexa: toque para falar, toque de novo para parar",
                        onDown = {
                            if (micGranted) vm.voiceDown() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        onUp = vm::voiceUp, size = small,
                        label = label(if (state.listening) "Ouvindo…" else "Alexa"),
                        active = state.listening,
                    )
                    RemoteButton(
                        Icons.Rounded.Keyboard, "Teclado",
                        onDown = { keyboardOpen = true }, onUp = {}, size = small, label = label("Teclado"),
                    )
                }

                if (state.touchpad) {
                    Touchpad(onStep = vm::step, onTap = { vm.press(RemoteKey.SELECT) }, modifier = Modifier.size(padSize))
                } else {
                    DPad(onDown = vm::directionDown, onUp = vm::directionUp, modifier = Modifier.size(padSize))
                }

                ButtonRow {
                    KeyButton(vm, RemoteKey.BACK, Icons.AutoMirrored.Rounded.ArrowBack, "Voltar", button, compact)
                    KeyButton(vm, RemoteKey.HOME, Icons.Rounded.Home, "Início", button, compact)
                    KeyButton(vm, RemoteKey.MENU, Icons.Rounded.Menu, "Menu", button, compact)
                }
                ButtonRow {
                    KeyButton(vm, RemoteKey.REWIND, Icons.Rounded.FastRewind, "Retroceder", button, compact)
                    KeyButton(vm, RemoteKey.PLAY_PAUSE, Icons.Rounded.PlayArrow, "Play/Pausa", button, compact, accent = true)
                    KeyButton(vm, RemoteKey.FAST_FORWARD, Icons.Rounded.FastForward, "Avançar", button, compact)
                }
                // Volume/mudo vão pela TV (HDMI-CEC); some quando o Fire TV diz que não controla o volume.
                if (state.info?.volumeSupported != false) {
                    ButtonRow {
                        KeyButton(vm, RemoteKey.VOLUME_DOWN, Icons.AutoMirrored.Rounded.VolumeDown, "Vol −", button, compact)
                        KeyButton(vm, RemoteKey.MUTE, Icons.AutoMirrored.Rounded.VolumeOff, "Mudo", button, compact)
                        KeyButton(vm, RemoteKey.VOLUME_UP, Icons.AutoMirrored.Rounded.VolumeUp, "Vol +", button, compact)
                    }
                }
            }
        }
    }

    if (appsOpen) {
        AppsSheet(
            state = state,
            onDismiss = { appsOpen = false },
            onRefresh = vm::loadApps,
            onLaunch = { app ->
                appsOpen = false
                vm.launch(app)
            },
        )
    }
    if (keyboardOpen) {
        KeyboardSheet(state.keyboardMode, vm, onDismiss = { keyboardOpen = false })
    }
}

@Composable
private fun ButtonRow(content: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth(),
    ) { content() }
}

@Composable
private fun KeyButton(
    vm: RemoteActions,
    key: RemoteKey,
    icon: ImageVector,
    label: String,
    size: Dp,
    compact: Boolean,
    accent: Boolean = false,
) {
    RemoteButton(
        icon = icon,
        description = label,
        onDown = { vm.keyDown(key) },
        onUp = { vm.keyUp(key) },
        size = size,
        label = if (compact) null else label,
        accent = accent,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteTopBar(state: UiState, vm: RemoteActions) {
    var menuOpen by remember { mutableStateOf(false) }
    val device = state.current ?: return
    val (dot, status) = when (state.conn) {
        Conn.CONNECTED -> Color(0xFF4CD964) to "Conectado"
        Conn.CONNECTING -> MaterialTheme.colorScheme.primary to "Conectando…"
        Conn.OFFLINE -> MaterialTheme.colorScheme.error to "Sem resposta · toque para reconectar"
    }
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { if (state.conn == Conn.OFFLINE) vm.reconnect() else vm.openDevices() }
                    .padding(vertical = 4.dp, horizontal = 4.dp),
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(device.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        actions = {
            IconButton(onClick = vm::openDevices) {
                Icon(Icons.Rounded.SwapHoriz, contentDescription = "Trocar de Fire TV")
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Mais opções")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    MenuToggle("Modo touchpad (deslizar)", state.touchpad) { vm.setTouchpad(!state.touchpad) }
                    MenuToggle("Botões de volume do celular controlam a TV", state.volumeKeys) { vm.setVolumeKeys(!state.volumeKeys) }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Configurações do Fire TV") },
                        leadingIcon = { Icon(Icons.Rounded.Settings, null) },
                        onClick = { menuOpen = false; vm.openFireTvSettings() },
                    )
                    DropdownMenuItem(
                        text = { Text("Ligar/desligar a TV (HDMI-CEC)") },
                        leadingIcon = { Icon(Icons.Rounded.Tv, null) },
                        onClick = { menuOpen = false; vm.press(RemoteKey.POWER) },
                    )
                    // Só aparece se o Fire TV tem um controle com localizador (Alexa Voice Remote Pro).
                    if ((state.info?.ringableRemotes ?: 0) > 0) {
                        DropdownMenuItem(
                            text = { Text("Fazer o controle físico tocar") },
                            leadingIcon = { Icon(Icons.Rounded.WifiFind, null) },
                            onClick = { menuOpen = false; vm.ringRemotes() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Reconectar") },
                        leadingIcon = { Icon(Icons.Rounded.Refresh, null) },
                        onClick = { menuOpen = false; vm.reconnect() },
                    )
                }
            }
        },
    )
}

@Composable
private fun MenuToggle(text: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = {
            if (checked) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary) else Spacer(Modifier.size(24.dp))
        },
        onClick = onClick,
    )
}
