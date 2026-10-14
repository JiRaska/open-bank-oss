// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import com.openbank.libs.testing.mtls.ListenerMaterial
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsExchange
import com.sun.net.httpserver.HttpsParameters
import com.sun.net.httpserver.HttpsServer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.tls.CertificateUpdatedEvent
import io.quarkus.tls.TlsConfiguration
import io.quarkus.tls.TlsConfigurationRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.Optional
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

class UpstreamClientMtlsTest {
    @Test
    fun `named TLS presents client identity and preserves bearer while trust only fails`() {
        Fixture().use { fixture ->
            fixture.client(false).get(fixture.url).use { assertThat(it.status).isEqualTo(502) }
            assertThat(fixture.identity.get()).isNull()
            fixture.client(true).get(fixture.url).use { assertThat(it.status).isEqualTo(200) }
            assertThat(fixture.identity.get()).contains("CN=fleet-workload-test")
            assertThat(fixture.bearer.get()).isEqualTo("Bearer test-edge-token")
        }
    }

    @Test
    fun `certificate renewal replaces captured context and rejects missing client identity`() {
        Fixture().use { fixture ->
            val client = fixture.client(true)
            client.get(fixture.url).use { assertThat(it.status).isEqualTo(200) }
            client.certificateUpdated(CertificateUpdatedEvent("another-client", fixture.configuration))
            client.get(fixture.url).use { assertThat(it.status).isEqualTo(200) }
            verify(exactly = 1) { fixture.configuration.createSSLContext() }
            fixture.clientContext = ListenerMaterial.sslContext(false)
            client.certificateUpdated(CertificateUpdatedEvent("upstream", fixture.configuration))
            client.get(fixture.url).use { assertThat(it.status).isEqualTo(502) }
            verify(exactly = 2) { fixture.configuration.createSSLContext() }
            fixture.clientContext = ListenerMaterial.sslContext(true)
            client.certificateUpdated(CertificateUpdatedEvent("upstream", fixture.configuration))
            client.get(fixture.url).use { assertThat(it.status).isEqualTo(200) }
            verify(exactly = 3) { fixture.configuration.createSSLContext() }
            assertThat(fixture.identity.get()).contains("CN=fleet-workload-test")
            assertThat(fixture.bearer.get()).isEqualTo("Bearer test-edge-token")
        }
    }

    private class Fixture : AutoCloseable {
        private val material = ListenerMaterial()
        private val config = material.start()
        private val directory = Path.of(config.getValue("quarkus.http.ssl.certificate.files")).parent
        private val password = System.getProperty("openbank.test.listener-parity.password").toCharArray()
        private val server = HttpsServer.create(InetSocketAddress("localhost", 0), 0)
        private val tokens = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        private val clients = mutableListOf<UpstreamClient>()
        val identity = AtomicReference<String>()
        val bearer = AtomicReference<String>()
        var clientContext: SSLContext = ListenerMaterial.sslContext(true)
        val configuration = mockk<TlsConfiguration>()
        private val registry = mockk<TlsConfigurationRegistry>()
        val url: String get() = "https://localhost:${server.address.port}/audit"

        init {
            every { configuration.isTrustAll } returns false
            every { configuration.createSSLContext() } answers { clientContext }
            every { registry.get("upstream") } returns Optional.of(configuration)
            configureServer()
            tokens.createContext("/realms/openbank/protocol/openid-connect/token") { exchange ->
                val response = """{"access_token":"test-edge-token","expires_in":300}""".toByteArray()
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            server.start()
            tokens.start()
        }

        fun client(named: Boolean) = UpstreamClient().apply {
            tlsConfigurationName = java.util.Optional.empty()
            clientSecret = "test-only-secret"
            tokenEndpointBase = "http://localhost:${tokens.address.port}/realms/openbank"
            tlsTrustCertificateFile = Optional.of(directory.resolve("tls.crt").toString())
            if (named) {
                tlsConfigurationName = Optional.of("upstream")
                tlsRegistry = registry
            }
        }.also { clients += it }

        private fun store(name: String) = KeyStore.getInstance("PKCS12").apply {
            Files.newInputStream(directory.resolve(name)).use { load(it, password) }
        }

        private fun configureServer() {
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(store("server.p12"), password)
            }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(store("client.p12"))
            }
            val context = SSLContext.getInstance("TLSv1.3").apply { init(keys.keyManagers, trust.trustManagers, null) }
            server.httpsConfigurator = object : HttpsConfigurator(context) {
                override fun configure(parameters: HttpsParameters) {
                    parameters.setSSLParameters(context.defaultSSLParameters.apply { needClientAuth = true })
                }
            }
            server.createContext("/audit") { exchange ->
                identity.set((exchange as HttpsExchange).sslSession.peerPrincipal.name)
                bearer.set(exchange.requestHeaders.getFirst("Authorization"))
                val response = "{}".toByteArray()
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
        }

        override fun close() {
            clients.forEach(UpstreamClient::closeTransport)
            server.stop(0)
            tokens.stop(0)
            material.stop()
        }
    }
}
