package com.edx0n.firetvremote.firetv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Um Fire TV conhecido. [token] é o x-client-token recebido no pareamento por PIN. */
data class FireTvDevice(
    val host: String,
    val name: String,
    val model: String? = null,
    val udn: String? = null,
    val token: String? = null,
    val dialUrl: String? = null,
) {
    val id: String get() = udn ?: host

    fun toJson(): JSONObject = JSONObject()
        .put("host", host)
        .put("name", name)
        .putOpt("model", model)
        .putOpt("udn", udn)
        .putOpt("token", token)
        .putOpt("dialUrl", dialUrl)

    companion object {
        fun fromJson(o: JSONObject) = FireTvDevice(
            host = o.getString("host"),
            name = o.optString("name", o.getString("host")),
            model = o.optString("model").ifEmpty { null },
            udn = o.optString("udn").ifEmpty { null },
            token = o.optString("token").ifEmpty { null },
            dialUrl = o.optString("dialUrl").ifEmpty { null },
        )

        fun from(tv: DiscoveredTv) = FireTvDevice(tv.host, tv.name, tv.model, tv.udn, dialUrl = tv.dialUrl)
    }
}

class DeviceStore(context: Context) {
    private val prefs = context.getSharedPreferences("firetv_remote", Context.MODE_PRIVATE)

    fun load(): List<FireTvDevice> = try {
        val array = JSONArray(prefs.getString(KEY_DEVICES, "[]"))
        (0 until array.length()).map { FireTvDevice.fromJson(array.getJSONObject(it)) }
    } catch (_: Exception) {
        emptyList()
    }

    fun upsert(device: FireTvDevice): List<FireTvDevice> {
        val others = load().filterNot { it.id == device.id || it.host == device.host }
        return save(listOf(device) + others)
    }

    fun remove(device: FireTvDevice): List<FireTvDevice> = save(load().filterNot { it.id == device.id })

    private fun save(devices: List<FireTvDevice>): List<FireTvDevice> {
        val array = JSONArray().apply { devices.forEach { put(it.toJson()) } }
        prefs.edit().putString(KEY_DEVICES, array.toString()).apply()
        return devices
    }

    var currentId: String?
        get() = prefs.getString(KEY_CURRENT, null)
        set(value) = prefs.edit().putString(KEY_CURRENT, value).apply()

    var volumeKeys: Boolean
        get() = prefs.getBoolean(KEY_VOLUME_KEYS, true)
        set(value) = prefs.edit().putBoolean(KEY_VOLUME_KEYS, value).apply()

    var touchpad: Boolean
        get() = prefs.getBoolean(KEY_TOUCHPAD, false)
        set(value) = prefs.edit().putBoolean(KEY_TOUCHPAD, value).apply()

    private companion object {
        const val KEY_DEVICES = "devices"
        const val KEY_CURRENT = "current"
        const val KEY_VOLUME_KEYS = "volume_keys"
        const val KEY_TOUCHPAD = "touchpad"
    }
}
