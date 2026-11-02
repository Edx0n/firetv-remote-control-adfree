package com.edx0n.firetvremote.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.edx0n.firetvremote.firetv.Http
import com.edx0n.firetvremote.firetv.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

private val cache = LruCache<String, ImageBitmap>(80)

/** Carregador de imagens mínimo (ícones dos apps), para não depender de uma biblioteca inteira. */
@Composable
fun NetImage(url: String?, modifier: Modifier = Modifier, placeholder: @Composable () -> Unit = {}) {
    var bitmap by remember(url) { mutableStateOf(url?.let { cache.get(it) }) }
    LaunchedEffect(url) {
        if (url == null || bitmap != null) return@LaunchedEffect
        bitmap = runCatching { load(url) }.getOrNull()?.also { cache.put(url, it) }
    }
    val image = bitmap
    if (image != null) {
        Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        Box(modifier) { placeholder() }
    }
}

private suspend fun load(url: String): ImageBitmap? {
    val client = if (url.startsWith("https://") && !isLocal(url)) Http.web else Http.lan
    val bytes = client.newCall(Request.Builder().url(url).build()).await().use { response ->
        if (!response.isSuccessful) return null
        response.body?.bytes()
    } ?: return null
    return withContext(Dispatchers.Default) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 256) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?.asImageBitmap()
    }
}

private fun isLocal(url: String): Boolean {
    val host = url.substringAfter("://").substringBefore('/').substringBefore(':')
    return host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")
}
