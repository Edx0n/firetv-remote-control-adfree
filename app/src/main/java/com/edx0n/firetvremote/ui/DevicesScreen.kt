package com.edx0n.firetvremote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edx0n.firetvremote.firetv.FireTvDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(state: UiState, vm: RemoteActions) {
    var manualOpen by rememberSaveable { mutableStateOf(false) }
    val canGoBack = state.current != null
    BackHandler(enabled = canGoBack) { vm.closeDevices() }
    LaunchedEffect(Unit) { vm.discover() }

    val pairedIds = state.devices.map { it.host }.toSet()
    val unpaired = state.discovered.filter { it.host !in pairedIds }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Seus Fire TVs") },
                navigationIcon = {
                    if (canGoBack) {
                        IconButton(onClick = vm::closeDevices) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Voltar")
                        }
                    }
                },
                actions = {
                    if (state.scanning) {
                        CircularProgressIndicator(Modifier.padding(end = 16.dp).size(22.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = vm::discover) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Procurar de novo")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (state.devices.isNotEmpty()) {
                item { SectionTitle("Pareados") }
                items(state.devices, key = { "p-" + it.id }) { device ->
                    DeviceRow(
                        name = device.name,
                        detail = listOfNotNull(device.model, device.host).joinToString(" · "),
                        online = state.discovered.any { it.host == device.host },
                        selected = device.id == state.current?.id,
                        action = if (device.token == null) "Parear" else null,
                        onClick = { vm.select(device) },
                        onRemove = { vm.forget(device) },
                    )
                }
            }

            item { SectionTitle(if (state.scanning) "Procurando na rede…" else "Encontrados na rede") }
            items(unpaired, key = { "d-" + it.host }) { tv ->
                DeviceRow(
                    name = tv.name,
                    detail = listOfNotNull(tv.model, tv.host).joinToString(" · "),
                    online = true,
                    selected = false,
                    action = "Parear",
                    onClick = { vm.startPairing(FireTvDevice.from(tv)) },
                    onRemove = null,
                )
            }
            if (!state.scanning && unpaired.isEmpty()) {
                item {
                    Text(
                        if (state.devices.isEmpty()) {
                            "Nenhum Fire TV encontrado. Confira se o celular está no mesmo Wi-Fi que a TV e se ela está ligada."
                        } else {
                            "Nenhum outro Fire TV encontrado."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }

            item {
                OutlinedButton(onClick = { manualOpen = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Adicionar pelo endereço IP")
                }
            }
            item {
                Text(
                    "O IP aparece no Fire TV em Configurações › Meu Fire TV › Sobre › Rede. " +
                        "Na primeira conexão a TV mostra um PIN de 4 dígitos para confirmar o pareamento.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }

    if (manualOpen) {
        ManualIpDialog(
            onDismiss = { manualOpen = false },
            onConfirm = { ip ->
                manualOpen = false
                vm.addManual(ip)
            },
        )
    }
    state.pairing?.let { PairingDialog(it, vm) }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun DeviceRow(
    name: String,
    detail: String,
    online: Boolean,
    selected: Boolean,
    action: String?,
    onClick: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 14.dp)) {
            Box {
                Icon(Icons.Rounded.Tv, contentDescription = null, modifier = Modifier.size(30.dp))
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (action != null) {
                TextButton(onClick = onClick) { Text(action) }
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Rounded.Close, contentDescription = "Esquecer $name", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ManualIpDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var ip by rememberSaveable { mutableStateOf("192.168.") }
    val valid = Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(ip.trim())
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Endereço IP do Fire TV") },
        text = {
            OutlinedTextField(
                value = ip,
                onValueChange = { ip = it.filter { c -> c.isDigit() || c == '.' } },
                singleLine = true,
                placeholder = { Text("192.168.0.20") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(ip.trim()) }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(ip.trim()) }, enabled = valid) { Text("Conectar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun PairingDialog(pairing: PairingUi, vm: RemoteActions) {
    var pin by rememberSaveable(pairing.target.host) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val waitingPin = pairing.phase == PairingUi.Phase.ENTER_PIN
    LaunchedEffect(waitingPin) { if (waitingPin) runCatching { focus.requestFocus() } }
    // Cada erro significa PIN novo (ou tentativa nova): começa o campo do zero.
    LaunchedEffect(pairing.error) { if (pairing.error != null) pin = "" }

    AlertDialog(
        onDismissRequest = vm::cancelPairing,
        title = { Text("Parear com ${pairing.target.name}") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                when (pairing.phase) {
                    PairingUi.Phase.REQUESTING, PairingUi.Phase.VERIFYING -> {
                        CircularProgressIndicator(Modifier.padding(16.dp))
                        Text(
                            if (pairing.phase == PairingUi.Phase.REQUESTING) "Pedindo um PIN para a TV…" else "Confirmando…",
                            textAlign = TextAlign.Center,
                        )
                    }
                    PairingUi.Phase.ENTER_PIN -> {
                        Text("Digite o PIN de 4 dígitos que apareceu na tela da TV.", textAlign = TextAlign.Center)
                        OutlinedTextField(
                            value = pin,
                            onValueChange = { value ->
                                pin = value.filter(Char::isDigit).take(4)
                                // Como no app oficial: confirma sozinho ao completar 4 dígitos.
                                if (pin.length == 4) vm.submitPin(pin)
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.headlineMedium.copy(textAlign = TextAlign.Center, letterSpacing = 8.sp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { vm.submitPin(pin) }),
                            modifier = Modifier.padding(top = 16.dp).focusRequester(focus),
                        )
                    }
                }
                pairing.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
                }
            }
        },
        confirmButton = {
            when {
                pairing.phase == PairingUi.Phase.ENTER_PIN ->
                    TextButton(onClick = { vm.submitPin(pin) }, enabled = pin.length == 4) { Text("Parear") }
                pairing.error != null ->
                    TextButton(onClick = vm::retryPairing) { Text("Tentar de novo") }
            }
        },
        dismissButton = {
            Row {
                if (waitingPin) TextButton(onClick = vm::retryPairing) { Text("Novo PIN") }
                TextButton(onClick = vm::cancelPairing) { Text("Cancelar") }
            }
        },
    )
}
