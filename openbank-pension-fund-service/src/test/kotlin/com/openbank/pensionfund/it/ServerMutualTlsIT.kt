// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.it

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManagerFactory

/** Exercises the fund service's actual TLS listener, required client auth, trust, and HTTP compatibility. */
@QuarkusTest
@TestProfile(ServerMutualTlsIT.RequiredClientCertificateProfile::class)
@QuarkusTestResource(PostgresTestResource::class, restrictToAnnotatedClass = true)
@QuarkusTestResource(ServerMutualTlsTestResource::class, restrictToAnnotatedClass = true)
class ServerMutualTlsIT {
    class RequiredClientCertificateProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.http.ssl.client-auth" to "required",
        )
    }

    @TestHTTPResource(value = "/q/health/live", tls = true)
    lateinit var https: URI

    @TestHTTPResource("/q/health/live")
    lateinit var http: URI

    @Test
    fun `trusted client reaches live health over TLS 1_3`() {
        val response = request(https, clientContext(trustServer = true, presentClient = true))
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.sslSession().orElseThrow().protocol).isEqualTo("TLSv1.3")
    }

    @Test
    fun `listener rejects a client without a certificate`() {
        assertThatThrownBy {
            request(https, clientContext(trustServer = true, presentClient = false))
        }.isInstanceOf(SSLHandshakeException::class.java)
    }

    @Test
    fun `client rejects an untrusted server certificate`() {
        assertThatThrownBy {
            request(https, clientContext(trustServer = false, presentClient = true))
        }.isInstanceOf(SSLHandshakeException::class.java)
    }

    @Test
    fun `HTTP compatibility listener remains available`() {
        val response = HttpClient.newBuilder().build().send(
            HttpRequest.newBuilder(http).timeout(java.time.Duration.ofSeconds(5)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertThat(response.statusCode()).isEqualTo(200)
    }

    private fun request(uri: URI, sslContext: SSLContext): HttpResponse<String> =
        HttpClient.newBuilder().sslContext(sslContext).connectTimeout(java.time.Duration.ofSeconds(5)).build().send(
            HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofSeconds(5)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun clientContext(trustServer: Boolean, presentClient: Boolean): SSLContext {
        val config = ConfigProvider.getConfig()
        val password = config.getValue("pensionfund.it.tls.client-store-password", String::class.java).toCharArray()
        val keyManagers = if (presentClient) {
            val storePath = config.getValue("pensionfund.it.tls.client-store", String::class.java)
            val store = KeyStore.getInstance("PKCS12").apply {
                Files.newInputStream(Path.of(storePath)).use { load(it, password) }
            }
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
                .keyManagers
        } else {
            null
        }
        val trustedPath = config.getValue(
            if (trustServer) "pensionfund.it.tls.server-certificate" else "pensionfund.it.tls.untrusted-certificate",
            String::class.java,
        )
        val trustedCertificate = Files.newInputStream(Path.of(trustedPath)).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it)
        }
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("test-peer", trustedCertificate)
        }
        val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(trustStore)
        }
        return SSLContext.getInstance("TLS").apply { init(keyManagers, trustManagers.trustManagers, null) }
    }
}
