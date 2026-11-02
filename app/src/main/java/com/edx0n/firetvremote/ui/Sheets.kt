package com.edx0n.firetvremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edx0n.firetvremote.firetv.FireTvApp
import com.edx0n.firetvremote.firetv.RemoteKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsSheet(
    state: UiState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onLaunch: (FireTvApp) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 20.dp, end = 8.dp)) {
            Text("Apps do Fire TV", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (state.appsLoading) {
                CircularProgressIndicator(Modifier.padding(12.dp).size(22.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, contentDescription = "Atualizar") }
            }
        }
        if (state.apps.isEmpty() && !state.appsLoading) {
            Text(
                "Nenhum app carregado. Toque em atualizar.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 92.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.heightIn(max = 560.dp).navigationBarsPadding(),
        ) {
            items(state.apps) { app ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onLaunch(app) }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                ) {
                    NetImage(
                        url = app.iconUrl,
                        modifier = Modifier.size(width = 84.dp, height = 48.dp).clip(RoundedCornerShape(10.dp)),
                        placeholder = {
                            Box(
                                Modifier.size(width = 84.dp, height = 48.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(app.name.take(1).uppercase(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                        },
                    )
                    Text(
                        app.name,
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * Teclado: o que é digitado aqui vai para o campo de texto aberto na TV (busca da Netflix,
 * YouTube, senha do Wi-Fi…). Em TVs novas o texto é espelhado inteiro a cada mudança; nas antigas,
 * letra por letra — o ViewModel decide pelo estado que a TV informa ao abrir.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyboardSheet(mode: KeyboardMode, vm: RemoteActions, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        vm.openKeyboard()
        runCatching { focus.requestFocus() }
    }
    val update = { value: String ->
        text = value
        vm.keyboardInput(value)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp).imePadding()) {
            Text("Digitar na TV", style = MaterialTheme.typography.titleLarge)
            Text(
                when (mode) {
                    KeyboardMode.CHECKING -> "Verificando o campo de texto da TV…"
                    KeyboardMode.MIRROR -> "O texto aparece na TV enquanto você digita."
                    KeyboardMode.LETTERS -> "Cada letra é enviada para a TV. Se nada aparecer, abra antes um campo de texto na TV (ex.: a busca de um app)."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            OutlinedTextField(
                value = text,
                onValueChange = update,
                placeholder = { Text("Digite aqui") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDismiss() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                RemoteButton(
                    Icons.AutoMirrored.Rounded.Backspace, "Apagar",
                    onDown = {
                        // Com texto digitado aqui, apaga daqui (e a TV acompanha); vazio, apaga direto na TV.
                        if (text.isNotEmpty()) {
                            update(text.substring(0, text.offsetByCodePoints(text.length, -1)))
                        } else {
                            vm.press(RemoteKey.BACKSPACE)
                        }
                    },
                    onUp = {}, size = 52.dp, label = "Apagar",
                )
                RemoteButton(
                    Icons.Rounded.ClearAll, "Limpar",
                    onDown = { update("") }, onUp = {}, size = 52.dp, label = "Limpar",
                )
                RemoteButton(
                    Icons.Rounded.Check, "Concluir",
                    onDown = onDismiss, onUp = {}, size = 52.dp, label = "Concluir", accent = true,
                )
            }
        }
    }
}
