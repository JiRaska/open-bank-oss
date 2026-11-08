// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.libs.security

import com.openbank.libs.authz.AuthzQuery
import com.openbank.libs.authz.OpaSidecarPolicyDecisionPoint
import com.openbank.libs.authz.PolicyDecisionException
import com.openbank.libs.authz.Principal
import com.openbank.libs.flags.EvalContext
import com.openbank.libs.flags.EvaluationReason
import com.openbank.libs.flags.FlagdProvider
import com.openbank.libs.productrevision.IrSignature
import com.openbank.libs.productrevision.IrSigningException
import com.openbank.libs.productrevision.OpenBaoTransitIrSigner
import com.openbank.libs.productrevision.RevisionIdentity
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class OpenBaoExchangeDeadlineTest {
    @Test
    fun `login deadline includes a response body that never completes`(@TempDir dir: Path) {
        val jwt = dir.resolve("synthetic-token").also { Files.writeString(it, "synthetic-jwt") }
        StalledPeer(200).use { peer ->
            withTransport(peer) { transport ->
                assertDeadline(peer) { OpenBaoKubernetesLogin(transport, "synthetic-role", jwt).login() }
            }
        }
    }

    @Test
    fun `Transit deadline covers successful and error response bodies`() {
        for (status in listOf(200, 503)) {
            StalledPeer(status).use { peer ->
                withTransport(peer) { transport ->
                    assertDeadline(peer) { protector(transport).encrypt("synthetic-plaintext".toByteArray()) }
                }
            }
        }
    }

    @Test
    fun `IR signing and verification share the body deadline and abort the connection`() {
        val identity = RevisionIdentity("SYNTHETIC", UUID(0, 1), 1, 1, "a".repeat(64))
        for (verify in listOf(false, true)) {
            StalledPeer(200).use { peer ->
                withTransport(peer) { transport ->
                    val signer = OpenBaoTransitIrSigner(transport, "synthetic-key", { "synthetic-token" })
                    val signature = IrSignature(
                        "synthetic-key",
                        OpenBaoTransitIrSigner.ALGORITHM,
                        "vault:v1:c3ludGhldGlj",
                    )
                    assertTimeoutPreemptively(Duration.ofSeconds(3)) {
                        assertThatThrownBy {
                            if (verify) signer.verify(identity, signature) else signer.sign(identity)
                        }.isInstanceOf(IrSigningException::class.java)
                            .hasCauseInstanceOf(HttpTimeoutException::class.java)
                    }
                    peer.assertClosedBeforeCleanup()
                }
            }
        }
    }

    @Test
    fun `a shorter request deadline is not extended by the transport default`() {
        StalledPeer(200).use { peer ->
            withTransport(peer, Duration.ofSeconds(10)) { transport ->
                val request = HttpRequest.newBuilder(peer.uri)
                    .timeout(Duration.ofMillis(500)).GET().build()
                assertTimeoutPreemptively(Duration.ofSeconds(3)) {
                    assertThatThrownBy { transport.send(request) }.isInstanceOf(HttpTimeoutException::class.java)
                }
                peer.assertClosedBeforeCleanup()
            }
        }
    }

    @Test
    fun `interrupting Transit cancels the exchange and preserves the interrupt flag`() {
        StalledPeer(200).use { peer ->
            withTransport(peer, Duration.ofSeconds(10)) { transport ->
                val failure = AtomicReference<FieldProtectionException?>()
                val interrupted = AtomicBoolean()
                val caller = Thread.ofVirtual().start {
                    try {
                        protector(transport).encrypt("synthetic-plaintext".toByteArray())
                    } catch (e: FieldProtectionException) {
                        failure.set(e)
                        interrupted.set(Thread.currentThread().isInterrupted)
                    }
                }
                try {
                    assertThat(peer.headersSent.await(2, TimeUnit.SECONDS)).isTrue()
                    caller.interrupt()
                    caller.join(2000)
                    assertThat(caller.isAlive).isFalse()
                    val observed = failure.get() ?: error("interruption must fail closed")
                    assertThat(observed).hasCauseInstanceOf(InterruptedException::class.java)
                    assertThat(interrupted.get()).isTrue()
                    peer.assertClosedBeforeCleanup()
                } finally {
                    caller.interrupt()
                    caller.join(2000)
                }
            }
        }
    }

    @Test
    fun `OPA timeout includes the body and remains a policy outage`() {
        for (status in listOf(200, 503)) {
            StalledPeer(status).use { peer ->
                withClient { client ->
                    val pdp = OpaSidecarPolicyDecisionPoint(
                        baseUrl = peer.uri.toString().trimEnd('/'),
                        timeout = Duration.ofMillis(500),
                        httpClient = client,
                    )
                    val query = AuthzQuery(principal = Principal("synthetic", "HUMAN"), action = "synthetic.read")
                    assertTimeoutPreemptively(Duration.ofSeconds(3)) {
                        val failure = assertThrows(PolicyDecisionException::class.java) {
                            runBlocking { pdp.allow(query) }
                        }
                        // Coroutine stack-trace recovery may copy the policy exception.
                        assertThat(generateSequence<Throwable>(failure) { it.cause }.any { it is HttpTimeoutException })
                            .isTrue()
                    }
                    peer.assertClosedBeforeCleanup()
                }
            }
        }
    }

    @Test
    fun `flag body timeout returns the caller default and reports provider unreachable`() {
        for (status in listOf(200, 503)) {
            StalledPeer(status).use { peer ->
                withClient { client ->
                    val provider = FlagdProvider(
                        baseUrl = peer.uri.toString().trimEnd('/'),
                        timeout = Duration.ofMillis(500),
                        httpClient = client,
                    )
                    assertTimeoutPreemptively(Duration.ofSeconds(3)) {
                        val result = provider.boolean("synthetic", false, EvalContext())
                        assertThat(result.value).isFalse()
                        assertThat(result.reason).isEqualTo(EvaluationReason.ERROR)
                        assertThat(result.errorCode).isEqualTo("PROVIDER_UNREACHABLE")
                    }
                    peer.assertClosedBeforeCleanup()
                }
            }
        }
    }

    private fun withClient(call: (HttpClient) -> Unit) {
        val client = HttpClient.newBuilder().build()
        try {
            call(client)
        } finally {
            client.shutdownNow()
            assertThat(client.awaitTermination(Duration.ofSeconds(2))).isTrue()
        }
    }

    private fun protector(transport: OpenBaoTransport) =
        OpenBaoTransitFieldProtector(transport, "synthetic-key", { "synthetic-token" }, ByteArray(32) { 1 })

    private fun assertDeadline(peer: StalledPeer, call: () -> Any) {
        assertTimeoutPreemptively(Duration.ofSeconds(3)) {
            assertThatThrownBy { call() }
                .isInstanceOf(FieldProtectionException::class.java)
                .hasCauseInstanceOf(HttpTimeoutException::class.java)
                .hasMessageNotContaining("synthetic-jwt")
                .hasMessageNotContaining("synthetic-plaintext")
        }
        peer.assertClosedBeforeCleanup()
    }

    private fun withTransport(
        peer: StalledPeer,
        timeout: Duration = Duration.ofMillis(500),
        call: (OpenBaoTransport) -> Unit,
    ) {
        val transport = OpenBaoTransport(peer.uri, requestTimeout = timeout, allowInsecureHttpForTests = true)
        try {
            call(transport)
        } finally {
            transport.httpClient.shutdownNow()
            assertThat(transport.httpClient.awaitTermination(Duration.ofSeconds(2))).isTrue()
        }
    }

    /** A real one-request peer; EOF is observed before any test/client cleanup. */
    internal class StalledPeer(status: Int) : AutoCloseable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val active = AtomicReference<Socket?>()
        private val closed = CountDownLatch(1)
        val headersSent = CountDownLatch(1)
        val uri: URI = URI.create("http://127.0.0.1:${listener.localPort}/")
        private val worker = Thread.ofVirtual().start {
            try {
                listener.accept().use { socket ->
                    active.set(socket)
                    socket.soTimeout = 5000
                    val input = socket.getInputStream()
                    val bytes = ByteArrayOutputStream()
                    while (!bytes.toString(Charsets.US_ASCII).endsWith("\r\n\r\n")) {
                        val next = input.read()
                        if (next < 0 || bytes.size() > 16384) throw IOException("invalid synthetic request")
                        bytes.write(next)
                    }
                    val length = bytes.toString(Charsets.US_ASCII).lineSequence()
                        .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                        ?.substringAfter(':')?.trim()?.toInt() ?: 0
                    if (input.readNBytes(length).size != length) throw IOException("incomplete synthetic request")
                    val responseHeaders = "HTTP/1.1 $status Synthetic\r\n" +
                        "Content-Length: 1\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().apply {
                        write(responseHeaders.toByteArray())
                        flush()
                    }
                    headersSent.countDown()
                    if (input.read() == -1) closed.countDown()
                }
            } catch (_: IOException) {
                // Timeout or cleanup must not be mistaken for a peer-observed EOF.
            }
        }

        fun assertClosedBeforeCleanup() {
            assertThat(headersSent.count).isZero()
            assertThat(closed.await(2, TimeUnit.SECONDS)).isTrue()
        }

        override fun close() {
            active.get()?.close()
            listener.close()
            worker.join(2000)
            assertThat(worker.isAlive).isFalse()
        }
    }
}
