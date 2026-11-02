package com.edx0n.firetvremote.firetv

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Teste contra um Fire TV de verdade, sem efeito na tela: sem token a TV recusa a tecla (403).
 * Só roda com a variável de ambiente FIRETV_HOST, ex.:
 *   FIRETV_HOST=192.168.0.20 gradlew :app:testDebugUnitTest --tests "*LiveTvTest*"
 */
class LiveTvTest {
    private val host: String? = System.getenv("FIRETV_HOST")

    @Before
    fun requireHost() = assumeTrue("defina FIRETV_HOST para rodar", host != null)

    @Test
    fun dialWakesTheServiceAndPinnedTlsReachesTheApi() = runBlocking {
        val tv = FireTvClient(host!!)
        assertTrue("DIAL não respondeu", tv.launchRemoteService())
        try {
            tv.key("home")
            fail("sem token a TV deveria recusar")
        } catch (e: FireTvException) {
            // Chegar ao 403 prova que o TLS com a CA da Amazon fixada funcionou.
            assertTrue("esperava 403, veio ${e.code}: ${e.message} (${e.cause})", e.unauthorized)
        }
    }
}
