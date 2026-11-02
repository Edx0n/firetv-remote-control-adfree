package com.edx0n.firetvremote.firetv

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

data class DiscoveredTv(
    val host: String,
    val name: String,
    val model: String?,
    val udn: String?,
    val dialUrl: String? = null,
)

/**
 * Descoberta de Fire TVs na rede local, como o app oficial faz:
 *
 * 1. M-SEARCH SSDP por `urn:dial-multiscreen-org:service:dial:1`, repetido a cada segundo;
 * 2. para cada resposta, baixa o descritor (`LOCATION`, ex.: `http://<ip>:60000/dd.xml`) e lê
 *    nome/modelo/UDN; o cabeçalho `Application-URL` dá a base do DIAL (`http://<ip>:8009/apps/`);
 * 3. é Fire TV se `GET <Application-URL>FireTVRemote` responder abaixo de 400.
 *
 * Se a rede bloquear multicast, [discover] ainda varre a sub-rede procurando a porta 8009.
 */
object FireTvDiscovery {
    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val DIAL_ST = "urn:dial-multiscreen-org:service:dial:1"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(2500, TimeUnit.MILLISECONDS)
            .build()
    }

    /** Procura por [durationMs] e avisa cada Fire TV assim que é confirmado. */
    suspend fun discover(context: Context, durationMs: Long = 6000, onFound: (DiscoveredTv) -> Unit): List<DiscoveredTv> {
        val found = ssdp(context, durationMs, onFound)
        return found.ifEmpty { scanSubnet(context).onEach(onFound) }
    }

    private suspend fun ssdp(context: Context, durationMs: Long, onFound: (DiscoveredTv) -> Unit): List<DiscoveredTv> =
        withContext(Dispatchers.IO) {
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            val lock = wifi?.createMulticastLock("firetv-ssdp")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            val results = mutableListOf<DiscoveredTv>()
            try {
                coroutineScope {
                    DatagramSocket().use { socket ->
                        socket.soTimeout = 250
                        val payload = ("M-SEARCH * HTTP/1.1\r\n" +
                            "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
                            "MAN: \"ssdp:discover\"\r\n" +
                            "MX: 1\r\n" +
                            "ST: $DIAL_ST\r\n\r\n").toByteArray()
                        val group = InetAddress.getByName(SSDP_ADDRESS)
                        val seen = HashSet<String>()
                        val buffer = ByteArray(4096)
                        val start = SystemClock.elapsedRealtime()
                        var nextSearch = start
                        while (SystemClock.elapsedRealtime() - start < durationMs) {
                            if (SystemClock.elapsedRealtime() >= nextSearch) {
                                socket.send(DatagramPacket(payload, payload.size, group, SSDP_PORT))
                                nextSearch += 1000
                            }
                            val packet = DatagramPacket(buffer, buffer.size)
                            try {
                                socket.receive(packet)
                            } catch (_: SocketTimeoutException) {
                                continue
                            }
                            val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                            if (!text.startsWith("HTTP/1.")) continue
                            val host = packet.address.hostAddress ?: continue
                            val location = headerValue(text, "LOCATION") ?: continue
                            if (!seen.add(host)) continue
                            launch {
                                describe(host, location)?.let { tv ->
                                    synchronized(results) { results += tv }
                                    onFound(tv)
                                }
                            }
                        }
                    }
                }
            } catch (_: IOException) {
                // Sem Wi-Fi ou multicast indisponível: a varredura da sub-rede cobre esse caso.
            } finally {
                lock?.release()
            }
            results
        }

    /** Varre a sub-rede (/22 ou menor) procurando a porta DIAL 8009 aberta. */
    suspend fun scanSubnet(context: Context): List<DiscoveredTv> = withContext(Dispatchers.IO) {
        val (address, prefix) = localIpv4(context) ?: return@withContext emptyList()
        if (prefix !in 22..30) return@withContext emptyList()
        val own = address.hostAddress
        val limit = Semaphore(48)
        hostsInSubnet(address, prefix)
            .filter { it != own }
            .map { host ->
                async { limit.withPermit { if (isPortOpen(host, 8009, 400)) probe(host) else null } }
            }
            .awaitAll()
            .filterNotNull()
    }

    /** Confirma que [host] é um Fire TV (usado também no IP digitado à mão). */
    suspend fun probe(host: String): DiscoveredTv? = withContext(Dispatchers.IO) {
        val dialBase = "http://${urlHost(host)}:8009/apps/"
        if (get(dialBase + "FireTVRemote") == null) return@withContext null
        // O descritor DIAL costuma ficar em :60000/dd.xml; sem ele, fica só o IP.
        describe(host, "http://${urlHost(host)}:60000/dd.xml")
            ?: DiscoveredTv(host, "Fire TV ($host)", null, null, dialBase + "FireTVRemote")
    }

    private fun describe(host: String, location: String): DiscoveredTv? {
        val description = get(location) ?: return null
        val dialBase = (description.applicationUrl ?: "http://${urlHost(host)}:8009/apps/").let { if (it.endsWith("/")) it else "$it/" }
        val dialUrl = dialBase + "FireTVRemote"
        get(dialUrl) ?: return null
        val xml = description.body
        return DiscoveredTv(
            host = host,
            name = xmlTag(xml, "friendlyName")?.takeIf { it.isNotBlank() } ?: "Fire TV ($host)",
            model = xmlTag(xml, "modelName"),
            udn = xmlTag(xml, "UDN")?.removePrefix("uuid:"),
            dialUrl = dialUrl,
        )
    }

    private class HttpText(val body: String, val applicationUrl: String?)

    /** GET simples; null se falhar ou responder 400+. */
    private fun get(url: String): HttpText? = try {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.code >= 400) null else HttpText(response.body?.string().orEmpty(), response.header("Application-URL"))
        }
    } catch (_: Exception) {
        null
    }

    private fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use {
            it.connect(InetSocketAddress(host, port), timeoutMs)
            true
        }
    } catch (_: IOException) {
        false
    }

    @Suppress("DEPRECATION") // allNetworks: única forma síncrona de achar o Wi-Fi quando há VPN ativa
    private fun localIpv4(context: Context): Pair<Inet4Address, Int>? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val networks = listOfNotNull(cm.activeNetwork) + cm.allNetworks.toList()
        for (network in networks.distinct()) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            val local = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!local) continue
            val link = cm.getLinkProperties(network)?.linkAddresses
                ?.firstOrNull { it.address is Inet4Address } ?: continue
            return (link.address as Inet4Address) to link.prefixLength
        }
        return null
    }

    private fun hostsInSubnet(address: Inet4Address, prefix: Int): List<String> {
        val ip = address.address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val network = ip and mask
        val count = 1L shl (32 - prefix)
        return (1 until count - 1).map { offset ->
            val v = network + offset
            "${(v shr 24) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 8) and 0xFF}.${v and 0xFF}"
        }
    }

    private fun headerValue(response: String, name: String): String? =
        response.lineSequence()
            .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()

    private fun xmlTag(xml: String, tag: String): String? =
        Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()?.let(::unescapeXml)

    private fun unescapeXml(s: String) = s
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'")
        .replace("&amp;", "&")
}

internal fun urlHost(host: String) = if (':' in host && !host.startsWith("[")) "[$host]" else host
