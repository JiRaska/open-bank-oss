// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.StandardConstants
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread

class SafeHttpClientTest {
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val publicAddr: InetAddress = InetAddress.getByName("93.184.216.34")
    private val servers = mutableListOf<ServerSocket>()

    @AfterEach
    fun close() = servers.forEach { it.close() }

    /** Minimal HTTP/1.1 stub: records the raw request head, answers with [response]. */
    private inner class Stub(
        private val server: ServerSocket = ServerSocket(0, 50, loopback),
        private val response: String = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok",
    ) {
        val requests = CopyOnWriteArrayList<String>()
        val sniNames = CopyOnWriteArrayList<String>()
        val port get() = server.localPort

        init {
            servers += server
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val s =
                        try {
                            server.accept()
                        } catch (_: IOException) {
                            break
                        }
                    try {
                        s.use { handle(it) }
                    } catch (_: IOException) {
                        // handshake refused by the client — that is the case some tests assert
                    }
                }
            }
        }

        private fun handle(s: Socket) {
            if (s is SSLSocket) {
                s.startHandshake()
                (s.session as? javax.net.ssl.ExtendedSSLSession)?.requestedServerNames?.forEach {
                    if (it.type == StandardConstants.SNI_HOST_NAME) sniNames += (it as SNIHostName).asciiName
                }
            }
            val input = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val head = generateSequence {
                input.readLine()
            }.takeWhile { it.isNotEmpty() }.joinToString("") { it + "\n" }
            requests += head
            s.getOutputStream().write(response.toByteArray(Charsets.ISO_8859_1))
            s.getOutputStream().flush()
        }
    }

    private fun policy(vararg entries: String) = EgressPolicy.fromConfig(entries.toList())

    @Test
    fun `allowlisted host is reached over plain http with the ORIGINAL Host header`() {
        val stub =
            Stub(response = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n2\r\nde\r\n0\r\n\r\n")
        val client = SafeHttpClient(policy("stub.test:${stub.port};http;private"), resolver = { listOf(loopback) })

        val resp = client.send(EgressRequest("GET", "http://stub.test:${stub.port}/a/b?c=d", mapOf("X-Trace" to "1")))

        assertThat(resp.status).isEqualTo(200)
        assertThat(resp.bodyAsString()).isEqualTo("abcde")
        assertThat(
            stub.requests.single(),
        ).startsWith("GET /a/b?c=d HTTP/1.1\n").contains("Host: stub.test:${stub.port}\n")
    }

    @Test
    fun `https is pinned to the vetted IP while SNI, Host and certificate identity use the name`(@TempDir dir: Path) {
        val (serverCtx, clientCtx) = tlsContexts(dir, "stub.test")
        val stub = Stub(server = serverCtx.serverSocketFactory.createServerSocket(0, 50, loopback) as SSLServerSocket)
        val client =
            SafeHttpClient(policy("stub.test:${stub.port};private"), resolver = {
                listOf(loopback)
            }, sslContext = clientCtx)

        val resp = client.send(EgressRequest("POST", "https://stub.test:${stub.port}/x", body = "hi".toByteArray()))

        assertThat(resp.status).isEqualTo(200)
        assertThat(stub.sniNames).containsExactly("stub.test")
        assertThat(stub.requests.single()).contains("Host: stub.test:${stub.port}\n").contains("Content-Length: 2\n")
    }

    @Test
    fun `https to a name the certificate does not cover fails the handshake`(@TempDir dir: Path) {
        val (serverCtx, clientCtx) = tlsContexts(dir, "other.test")
        val stub = Stub(server = serverCtx.serverSocketFactory.createServerSocket(0, 50, loopback) as SSLServerSocket)
        val client =
            SafeHttpClient(policy("stub.test:${stub.port};private"), resolver = {
                listOf(loopback)
            }, sslContext = clientCtx)

        assertThatThrownBy { client.send(EgressRequest("GET", "https://stub.test:${stub.port}/")) }
            .isInstanceOf(SSLHandshakeException::class.java)
        assertThat(stub.requests).isEmpty()
    }

    @Test
    fun `a name that resolves to loopback is refused and nothing connects`() {
        val stub = Stub()
        val client = SafeHttpClient(policy("stub.test:${stub.port};http"), resolver = { listOf(loopback) })

        assertThatThrownBy { client.send(EgressRequest("GET", "http://stub.test:${stub.port}/")) }
            .isInstanceOfSatisfying(EgressDeniedException::class.java) {
                assertThat(it.decision.reason).isEqualTo(EgressDenialReason.RESOLVED_TO_FORBIDDEN_ADDRESS)
            }
        Thread.sleep(SETTLE_MS)
        assertThat(stub.requests).isEmpty()
    }

    @Test
    fun `a name that resolves to the metadata address is refused even with the private exemption`() {
        val connects = AtomicInteger()
        val client =
            SafeHttpClient(
                policy("stub.test;private"),
                resolver = { listOf(InetAddress.getByName("169.254.169.254")) },
                connector = { _, _ -> connects.incrementAndGet().let { throw IOException("must not connect") } },
            )

        assertThatThrownBy { client.send(EgressRequest("GET", "https://stub.test/latest/meta-data/")) }
            .isInstanceOf(EgressDeniedException::class.java)
        assertThat(connects.get()).isZero()
    }

    @Test
    fun `DNS rebinding - the name is resolved once and the connection goes to the vetted address`() {
        // First answer public (passes the check), every later answer loopback. A client that
        // checks one lookup and connects via another would reach the stub on 127.0.0.1.
        val stub = Stub()
        val lookups = AtomicInteger()
        val connectedTo = CopyOnWriteArrayList<InetSocketAddress>()
        val client =
            SafeHttpClient(
                policy("rebind.test:${stub.port};http"),
                resolver = { if (lookups.getAndIncrement() == 0) listOf(publicAddr) else listOf(loopback) },
                // Record and stop: the test must not open a real connection to a public address.
                connector = { addr, _ ->
                    connectedTo += addr
                    throw IOException("test connector: recorded $addr")
                },
            )

        runCatching { client.send(EgressRequest("GET", "http://rebind.test:${stub.port}/")) }

        assertThat(lookups.get()).isEqualTo(1)
        assertThat(connectedTo.map { it.address }).containsExactly(publicAddr)
        Thread.sleep(SETTLE_MS)
        assertThat(stub.requests).isEmpty()
    }

    @Test
    fun `a redirect to a private address is returned, never followed`() {
        val internal = Stub()
        val redirector =
            Stub(
                response =
                "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${internal.port}/admin\r\n" +
                    "Content-Length: 0\r\n\r\n",
            )
        val client =
            SafeHttpClient(policy("stub.test:${redirector.port};http;private"), resolver = { listOf(loopback) })

        val resp = client.send(EgressRequest("GET", "http://stub.test:${redirector.port}/"))

        assertThat(resp.status).isEqualTo(302)
        assertThat(resp.header("Location")).endsWith("/admin")
        Thread.sleep(SETTLE_MS)
        assertThat(internal.requests).isEmpty()
        // and following it by hand goes back through the policy
        assertThatThrownBy { client.send(EgressRequest("GET", resp.header("Location")!!)) }
            .isInstanceOfSatisfying(EgressDeniedException::class.java) {
                assertThat(it.decision.reason).isEqualTo(EgressDenialReason.IP_LITERAL)
            }
    }

    @Test
    fun `URL-level bypasses are refused before any DNS lookup`() {
        val lookups = AtomicInteger()
        val client =
            SafeHttpClient(policy("api.example.com"), resolver = {
                lookups.incrementAndGet().let { listOf(publicAddr) }
            })
        listOf(
            "https://api.example.com@evil.example.org/",
            "https://evil.example.org/",
            "http://api.example.com/",
            "https://2130706433/",
            "https://[::ffff:169.254.169.254]/",
            "https://api.example.com:8443/",
        ).forEach { url ->
            assertThatThrownBy {
                client.send(EgressRequest("GET", url))
            }.`as`(url).isInstanceOf(EgressDeniedException::class.java)
        }
        assertThat(lookups.get()).isZero()
    }

    @Test
    fun `header injection and managed headers are rejected`() {
        val connects = AtomicInteger()
        val client =
            SafeHttpClient(
                policy("api.example.com"),
                resolver = { listOf(publicAddr) },
                connector = { _, _ -> connects.incrementAndGet().let { throw IOException("must not connect") } },
            )
        listOf(mapOf("Host" to "evil"), mapOf("X-A" to "v\r\nHost: evil"), mapOf("X\r\nB" to "v")).forEach { h ->
            assertThatThrownBy { client.send(EgressRequest("GET", "https://api.example.com/", h)) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(connects.get()).isZero()
    }

    private fun tlsContexts(dir: Path, san: String): Pair<SSLContext, SSLContext> {
        val ks = dir.resolve("ks.p12").toString()
        val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
        val proc =
            ProcessBuilder(
                keytool, "-genkeypair", "-alias", "s", "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=$san", "-ext", "san=dns:$san", "-validity", "1",
                "-keystore", ks, "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit",
            ).redirectErrorStream(true).start()
        check(proc.waitFor() == 0) { proc.inputStream.readAllBytes().decodeToString() }
        val store = KeyStore.getInstance("PKCS12").apply {
            java.io.File(ks).inputStream().use { load(it, "changeit".toCharArray()) }
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(store, "changeit".toCharArray())
        }
        val trust = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        trust.setCertificateEntry("s", store.getCertificate("s"))
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        val server = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val client = SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
        return server to client
    }

    companion object {
        private const val SETTLE_MS = 200L
    }
}
