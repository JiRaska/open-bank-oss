// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.it

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
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
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManagerFactory

/** Real listeners, PEM key loading, trust and hostname verification during the additive rollout. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(ServerTlsTestResource::class, restrictToAnnotatedClass = true)
class ServerTlsIT {
    @TestHTTPResource(value = "/q/health/live", tls = true)
    lateinit var https: URI

    @TestHTTPResource("/q/health/live")
    lateinit var http: URI

    @Test
    fun `trusted server negotiates TLS 1_3 and retains the HTTP compatibility listener`() {
        val path = ConfigProvider.getConfig().getValue("quarkus.http.ssl.certificate.files", String::class.java)
        val certificate = Files.newInputStream(Path.of(path)).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it)
        }
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("ephemeral-server", certificate)
        }
        val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(trustStore)
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, trustManagers.trustManagers, null) }
        val client = HttpClient.newBuilder().sslContext(context).build()
        val secure = client.send(HttpRequest.newBuilder(https).GET().build(), HttpResponse.BodyHandlers.ofString())
        assertThat(secure.statusCode()).isEqualTo(200)
        assertThat(secure.sslSession().orElseThrow().protocol).isEqualTo("TLSv1.3")
        val compatible = client.send(HttpRequest.newBuilder(http).GET().build(), HttpResponse.BodyHandlers.ofString())
        assertThat(compatible.statusCode()).isEqualTo(200)
    }

    @Test
    fun `default trust rejects the ephemeral server certificate`() {
        val context = SSLContext.getInstance("TLS").apply { init(null, null, null) }
        val client = HttpClient.newBuilder().sslContext(context).build()
        assertThatThrownBy {
            client.send(HttpRequest.newBuilder(https).GET().build(), HttpResponse.BodyHandlers.ofString())
        }.isInstanceOf(SSLHandshakeException::class.java)
    }
}
