// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpRequest
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateException
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * [OpenBaoTransport.pemTrustSslContext] against a real TLS listener: the CA that issued the
 * listener is trusted, an unrelated CA is refused, and a file with no certificate fails at
 * construction rather than at the first Transit call.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenBaoTransportTrustTest {
    private lateinit var dir: Path
    private lateinit var server: HttpsServer
    private lateinit var serverCa: Path
    private lateinit var otherCa: Path

    @BeforeAll
    fun start(@TempDir tempDir: Path) {
        dir = tempDir
        val serverKeys = keypair("server")
        serverCa = exportPem("server", serverKeys)
        otherCa = exportPem("other", keypair("other"))

        val ks = KeyStore.getInstance("PKCS12").apply {
            Files.newInputStream(serverKeys).use { load(it, PASSWORD.toCharArray()) }
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(ks, PASSWORD.toCharArray())
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(ctx)
            createContext("/v1/sys/health") { ex ->
                ex.sendResponseHeaders(HTTP_OK, -1)
                ex.close()
            }
            start()
        }
    }

    @AfterAll
    fun stop() = server.stop(0)

    @Test
    fun `the CA that issued the listener is trusted`() {
        val transport = OpenBaoTransport(address(), OpenBaoTransport.pemTrustSslContext(serverCa))
        val response = transport.httpClient.send(get(transport), OpenBaoTransport.boundedBody())
        assertThat(response.statusCode()).isEqualTo(HTTP_OK)
    }

    @Test
    fun `an unrelated CA is refused`() {
        val transport = OpenBaoTransport(address(), OpenBaoTransport.pemTrustSslContext(otherCa))
        assertThatThrownBy { transport.httpClient.send(get(transport), OpenBaoTransport.boundedBody()) }
            .isInstanceOf(IOException::class.java)
    }

    @Test
    fun `a CA file with no certificate fails at construction`() {
        val empty = Files.writeString(dir.resolve("empty.pem"), "")
        assertThatThrownBy { OpenBaoTransport.pemTrustSslContext(empty) }
            .isInstanceOfAny(IllegalArgumentException::class.java, CertificateException::class.java)
    }

    private fun address() = URI.create("https://localhost:${server.address.port}")

    private fun get(transport: OpenBaoTransport) =
        HttpRequest.newBuilder().uri(transport.uri("v1/sys/health")).GET().build()

    private fun keypair(alias: String): Path {
        val out = dir.resolve("$alias.p12")
        keytool(
            "-genkeypair", "-alias", alias, "-keyalg", "EC", "-groupname", "secp256r1",
            "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "2",
            "-storetype", "PKCS12", "-keystore", out.toString(), "-storepass", PASSWORD,
        )
        return out
    }

    private fun exportPem(alias: String, store: Path): Path {
        val ks = KeyStore.getInstance("PKCS12").apply {
            Files.newInputStream(store).use { load(it, PASSWORD.toCharArray()) }
        }
        val der = ks.getCertificate(alias).encoded
        val pem = "-----BEGIN CERTIFICATE-----\n" +
            Base64.getMimeEncoder(PEM_LINE, "\n".toByteArray()).encodeToString(der) +
            "\n-----END CERTIFICATE-----\n"
        return Files.writeString(dir.resolve("$alias.pem"), pem)
    }

    private fun keytool(vararg args: String) {
        val bin = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
        val proc = ProcessBuilder(listOf(bin) + args).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        check(proc.waitFor() == 0) { "keytool failed: $output" }
    }

    private companion object {
        const val PASSWORD = "test-only-store"
        const val HTTP_OK = 200
        const val PEM_LINE = 64
    }
}
