package com.edx0n.firetvremote.firetv

import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Clientes HTTP do app.
 *
 * A API do Fire TV (porta 8080) usa um certificado emitido pela CA interna "Turnstile Server"
 * da Amazon (Lab 126). Igual ao app oficial, [lan] confia **somente** nessa CA — que vai
 * embutida abaixo — e ignora o nome do host (a TV é acessada por IP). [web] mantém a
 * verificação normal do sistema para o resto (ícones dos apps, no CDN da Amazon).
 */
object Http {
    private const val TURNSTILE_ROOT = """-----BEGIN CERTIFICATE-----
MIICGDCCAb6gAwIBAgIQMCbLRSMSZeJ8CrNo77s8HzAKBggqhkjOPQQDAjBsMQsw
CQYDVQQGEwJVUzEPMA0GA1UECgwGQW1hem9uMRAwDgYDVQQLDAdMYWIgMTI2MQ4w
DAYDVQQIDAVUZXhhczEZMBcGA1UEAwwQVHVybnN0aWxlIFNlcnZlcjEPMA0GA1UE
BwwGQXVzdGluMB4XDTIwMDkyOTAwNTYzMloXDTQwMDkyOTAxNTYzMlowbDELMAkG
A1UEBhMCVVMxDzANBgNVBAoMBkFtYXpvbjEQMA4GA1UECwwHTGFiIDEyNjEOMAwG
A1UECAwFVGV4YXMxGTAXBgNVBAMMEFR1cm5zdGlsZSBTZXJ2ZXIxDzANBgNVBAcM
BkF1c3RpbjBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABIpSQp54uGLOXZSLqFrX
YRr9FQfr7NLLIuBAwjuqd3UmoRGaNSmwWkK76l0x5X51Wpl61SEAg19SmxQsIK39
njmjQjBAMA8GA1UdEwEB/wQFMAMBAf8wHQYDVR0OBBYEFCQD+GE4x4I+++GOe4LI
Dfn9cRqvMA4GA1UdDwEB/wQEAwIBhjAKBggqhkjOPQQDAgNIADBFAiAlhAjUdehX
shT/1YGqirPCKDdcdkOSd0zoQIz+0CNeiQIhAM+zsJV1s09va971oxmvA1N4c7Uj
FIW4fxlgaJwQD+Jl
-----END CERTIFICATE-----"""

    private val turnstileTrust: X509TrustManager by lazy {
        val root = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(TURNSTILE_ROOT.toByteArray()))
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("turnstile", root)
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore)
        factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    val lan: OkHttpClient by lazy { pinned(turnstileTrust) }

    /** Cliente com outra âncora de confiança (usado nos testes, com um certificado de teste). */
    fun pinned(trust: X509TrustManager): OkHttpClient {
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trust)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    val web: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
