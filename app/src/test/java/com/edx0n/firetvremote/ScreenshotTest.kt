package com.edx0n.firetvremote

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.app.ActivityOptionsCompat
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.edx0n.firetvremote.firetv.DiscoveredTv
import com.edx0n.firetvremote.firetv.FireTvApp
import com.edx0n.firetvremote.firetv.FireTvDevice
import com.edx0n.firetvremote.firetv.RemoteKey
import com.edx0n.firetvremote.ui.Conn
import com.edx0n.firetvremote.ui.DevicesScreen
import com.edx0n.firetvremote.ui.Direction
import com.edx0n.firetvremote.ui.RemoteActions
import com.edx0n.firetvremote.ui.RemoteScreen
import com.edx0n.firetvremote.ui.UiState
import com.edx0n.firetvremote.ui.theme.FireRemoteTheme
import org.junit.Rule
import org.junit.Test

/** Renderiza as telas principais em PNG (app/src/test/snapshots) para conferir o layout. */
class ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        theme = "android:Theme.Material.NoActionBar",
    )

    private val tv = FireTvDevice("192.168.0.20", "Fire TV Stick da Sala", "AFTSSS", "0000-sala", "token")
    private val connected = UiState(devices = listOf(tv), current = tv, conn = Conn.CONNECTED)

    @Test
    fun remote() = snapshot { RemoteScreen(connected, NoActions) }

    @Test
    fun remoteTouchpadOffline() = snapshot { RemoteScreen(connected.copy(touchpad = true, conn = Conn.OFFLINE), NoActions) }

    @Test
    fun remoteSmallPhone() {
        paparazzi.unsafeUpdateConfig(deviceConfig = DeviceConfig.NEXUS_5)
        snapshot { RemoteScreen(connected, NoActions) }
    }

    @Test
    fun devices() = snapshot {
        DevicesScreen(
            UiState(
                devices = listOf(tv),
                discovered = listOf(tv.toDiscovered(), DiscoveredTv("192.168.0.21", "Fire TV do Quarto", "AFTKA", "0000-quarto")),
            ),
            NoActions,
        )
    }

    private fun FireTvDevice.toDiscovered() = DiscoveredTv(host, name, model, udn)

    private fun snapshot(content: @Composable () -> Unit) = paparazzi.snapshot {
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides NoRegistry) {
            FireRemoteTheme(content)
        }
    }
}

private object NoRegistry : ActivityResultRegistryOwner {
    override val activityResultRegistry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) = Unit
    }
}

private object NoActions : RemoteActions {
    override fun openDevices() = Unit
    override fun closeDevices() = Unit
    override fun discover() = Unit
    override fun addManual(ip: String) = Unit
    override fun select(device: FireTvDevice) = Unit
    override fun forget(device: FireTvDevice) = Unit
    override fun startPairing(device: FireTvDevice) = Unit
    override fun retryPairing() = Unit
    override fun submitPin(pin: String) = Unit
    override fun cancelPairing() = Unit
    override fun reconnect() = Unit
    override fun press(key: RemoteKey) = Unit
    override fun keyDown(key: RemoteKey) = Unit
    override fun keyUp(key: RemoteKey) = Unit
    override fun directionDown(direction: Direction) = Unit
    override fun directionUp(direction: Direction) = Unit
    override fun step(direction: Direction) = Unit
    override fun openKeyboard() = Unit
    override fun keyboardInput(text: String) = Unit
    override fun loadApps() = Unit
    override fun launch(app: FireTvApp) = Unit
    override fun openFireTvSettings() = Unit
    override fun ringRemotes() = Unit
    override fun voiceDown() = Unit
    override fun voiceUp() = Unit
    override fun setTouchpad(enabled: Boolean) = Unit
    override fun setVolumeKeys(enabled: Boolean) = Unit
    override fun messageShown() = Unit
}
