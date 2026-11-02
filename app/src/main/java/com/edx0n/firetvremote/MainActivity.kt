package com.edx0n.firetvremote

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edx0n.firetvremote.ui.DevicesScreen
import com.edx0n.firetvremote.ui.RemoteScreen
import com.edx0n.firetvremote.ui.RemoteViewModel
import com.edx0n.firetvremote.ui.theme.FireRemoteTheme

class MainActivity : ComponentActivity() {
    private val vm: RemoteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FireRemoteTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                if (state.current == null || state.showDevices) {
                    DevicesScreen(state, vm)
                } else {
                    RemoteScreen(state, vm)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground()
    }

    // Botões de volume do celular controlam o volume da TV enquanto o controle está aberto.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        vm.onHardwareKey(keyCode, down = true, repeat = event.repeatCount) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        vm.onHardwareKey(keyCode, down = false, repeat = 0) || super.onKeyUp(keyCode, event)
}
