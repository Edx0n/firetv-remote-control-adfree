package com.edx0n.firetvremote.firetv

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Confere o formato exato das requisições (o mesmo do app oficial) contra um "Fire TV" falso em HTTPS. */
class FireTvClientTest {
    private val api = MockWebServer()
    private val dial = MockWebServer()
    private lateinit var http: okhttp3.OkHttpClient

    @Before
    fun setUp() {
        // Uma CA de teste no lugar da "Turnstile Server" da Amazon: o cliente confia só nela.
        val root = HeldCertificate.Builder().certificateAuthority(0).build()
        val server = HeldCertificate.Builder().signedBy(root).addSubjectAlternativeName("localhost").build()
        api.useHttps(HandshakeCertificates.Builder().heldCertificate(server, root.certificate).build().sslSocketFactory(), false)
        http = Http.pinned(HandshakeCertificates.Builder().addTrustedCertificate(root.certificate).build().trustManager)
        api.start()
        dial.start()
    }

    @After
    fun tearDown() {
        api.shutdown()
        dial.shutdown()
    }

    private fun client(token: String? = null, apiPort: Int = api.port) =
        FireTvClient("127.0.0.1", token, "http://127.0.0.1:${dial.port}/apps/FireTVRemote", apiPort, http)

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun pairingSendsFriendlyNameAndKeepsToken() = runBlocking {
        api.enqueue(json("""{"description":"OK"}"""))
        api.enqueue(json("""{"description":"token-123"}"""))
        val tv = client()

        tv.displayPin("Controle (Pixel)")
        val result = tv.verifyPin("1234")

        val display = api.takeRequest()
        assertEquals("POST", display.method)
        assertEquals("/v1/FireTV/pin/display", display.path)
        assertEquals("0987654321", display.getHeader("x-api-key"))
        assertEquals("application/json; charset=utf-8", display.getHeader("Content-Type"))
        assertNotNull(display.getHeader("x-amzn-request-id"))
        assertNull(display.getHeader("x-client-token"))
        assertEquals("Controle (Pixel)", JSONObject(display.body.readUtf8()).getString("friendlyName"))

        val verify = api.takeRequest()
        assertEquals("/v1/FireTV/pin/verify", verify.path)
        assertEquals("1234", JSONObject(verify.body.readUtf8()).getString("pin"))
        assertEquals(PinResult.Paired("token-123"), result)
        assertEquals("token-123", tv.token)
    }

    @Test
    fun emptyDescriptionMeansWrongPin() = runBlocking {
        api.enqueue(json("""{"description":""}"""))
        assertEquals(PinResult.Wrong, client().verifyPin("0000"))
    }

    @Test
    fun keyPressHoldAndHome() = runBlocking {
        repeat(4) { api.enqueue(MockResponse()) }
        val tv = client("abc")

        tv.send(RemoteKey.UP)
        tv.send(RemoteKey.SELECT, KeyAction.DOWN)
        tv.send(RemoteKey.HOME)

        val press = api.takeRequest()
        assertEquals("POST", press.method)
        assertEquals("/v1/FireTV?action=dpad_up", press.path)
        assertEquals("abc", press.getHeader("x-client-token"))
        assertEquals(0L, press.bodySize)

        val down = api.takeRequest()
        assertEquals("/v1/FireTV?action=select", down.path)
        assertEquals("keyDown", JSONObject(down.body.readUtf8()).getString("keyActionType"))

        // Início = wakeup e depois home, como o app oficial.
        assertEquals("/v1/FireTV?action=wakeup", api.takeRequest().path)
        assertEquals("/v1/FireTV?action=home", api.takeRequest().path)
    }

    @Test
    fun mediaUsesStringsAndNestedHold() = runBlocking {
        repeat(2) { api.enqueue(MockResponse()) }
        val tv = client("abc")

        tv.send(RemoteKey.REWIND)
        tv.send(RemoteKey.FAST_FORWARD, KeyAction.UP)

        val rewind = api.takeRequest()
        assertEquals("/v1/media?action=scan", rewind.path)
        val body = JSONObject(rewind.body.readUtf8())
        assertEquals("back", body.getString("direction"))
        assertEquals("10", body.get("durationInSeconds"))
        assertEquals("1", body.get("speed"))

        val hold = JSONObject(api.takeRequest().body.readUtf8())
        assertEquals("forward", hold.getString("direction"))
        assertEquals("keyUp", hold.getJSONObject("keyAction").getString("keyActionType"))
    }

    @Test
    fun forbiddenMeansUnpaired() = runBlocking {
        api.enqueue(json("""{"description":"Request unauthorized, missing client token"}""").setResponseCode(403))
        try {
            client("stale").key("home")
            fail("deveria falhar")
        } catch (e: FireTvException) {
            assertTrue(e.unauthorized)
            assertEquals("Request unauthorized, missing client token", e.message)
        }
    }

    @Test
    fun olderTvsAnswer405OnStatusAndUseV1Routes() = runBlocking {
        api.enqueue(MockResponse().setResponseCode(405))
        api.enqueue(json("""{"isVolumeControlsSupported":false,"ringableRemoteCount":1}"""))

        val info = client("abc").info()

        assertEquals("/v1/FireTV/status", api.takeRequest().path)
        assertEquals("/v1/FireTV", api.takeRequest().path)
        assertFalse(info.v2)
        assertEquals(false, info.volumeSupported)
        assertEquals(1, info.ringableRemotes)
    }

    @Test
    fun appsListParsesIconsAndShortcuts() = runBlocking {
        api.enqueue(
            json(
                """[
                  {"appId":"com.netflix.ninja","name":"Netflix","tvIconArt":"https://m.media-amazon.com/images/I/abc.png","iconArtSmallUri":"https://x/small.png"},
                  {"appId":"com.amazon.x","name":"Ao vivo","iconArtSmallUri":"https://x/live.jpg","isShortcutApp":true,"appShortcutLaunchIntent":"amazon.intent.LIVE"},
                  {"name":"sem id"}
                ]""",
            ),
        )

        val apps = client("abc").apps(v2 = true)

        assertEquals("/v1/FireTV/appsV2", api.takeRequest().path)
        assertEquals(2, apps.size)
        assertEquals(FireTvApp("com.netflix.ninja", "Netflix", "https://m.media-amazon.com/images/I/abc._SX210_.png"), apps[0])
        assertEquals("amazon.intent.LIVE", apps[1].shortcutIntent)
        assertEquals("https://x/live.jpg", apps[1].iconUrl)
    }

    @Test
    fun unreachableServiceIsWokenThroughDial() = runBlocking {
        val closedPort = api.port
        api.shutdown() // ninguém escutando na porta da API: conexão recusada
        dial.enqueue(MockResponse().setResponseCode(200))

        try {
            client("abc", apiPort = closedPort).key("home")
            fail("deveria falhar")
        } catch (e: FireTvException) {
            assertFalse(e.unauthorized)
        }

        val wake = dial.takeRequest(2, TimeUnit.SECONDS) ?: error("DIAL não foi chamado")
        assertEquals("POST", wake.method)
        assertEquals("/apps/FireTVRemote", wake.path)
        assertTrue(wake.getHeader("Content-Type").orEmpty().startsWith("text/plain"))
        assertEquals("0987654321", wake.getHeader("x-api-key"))
    }
}
