// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.libs.security

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Path
import java.security.KeyStore
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

class SafeHttpCallDeadlineTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test
    fun `whole call deadline closes a blocked plain request write before cleanup`() {
        val server = ServerSocket().apply {
            receiveBufferSize = 1024
            bind(InetSocketAddress(loopback, 0))
        }
        stalledUpload(server, null, Duration.ofMillis(150))
    }

    @Test
    fun `whole call deadline closes a blocked verified TLS request write before cleanup`(@TempDir dir: Path) {
        val (context, anchors) = SafeHttpClientTest().tlsContexts(dir, "stub.test")
        val server = (context.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
            receiveBufferSize = 1024
            bind(InetSocketAddress(loopback, 0))
        }
        stalledUpload(server, anchors, Duration.ofSeconds(2))
    }

    @Test
    fun `completed calls immediately remove their deadline tasks`() {
        ServerSocket(0, 50, loopback).use { server ->
            val peer = Thread.ofPlatform().daemon().start {
                repeat(10) {
                    server.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        while (input.readLine()?.isNotEmpty() == true) {
                            // Drain the request head before returning the response.
                        }
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray())
                    }
                }
            }
            try {
                val client = SafeHttpClient(policy(server.localPort), resolver = { listOf(loopback) })
                repeat(10) {
                    assertThat(
                        client.send(EgressRequest("GET", "http://stub.test:${server.localPort}/")).bodyAsString(),
                    )
                        .isEqualTo("ok")
                }
                assertThat(deadlines().queue).isEmpty()
                assertThat(deadlines().corePoolSize).isEqualTo(1)
                assertThat(deadlines().removeOnCancelPolicy).isTrue()
            } finally {
                server.close()
                peer.join(2000)
                assertThat(peer.isAlive).isFalse()
            }
        }
    }

    @Test
    fun `post connect failure cancels its timer and closes its raw socket`() {
        ServerSocket(0, 50, loopback).use { server ->
            val raw = AtomicReference<Socket>()
            val client = SafeHttpClient(
                policy(server.localPort),
                resolver = { listOf(loopback) },
                connector = { addr, timeout ->
                    object : Socket() {
                        override fun setSoTimeout(timeout: Int): Unit = throw IOException("synthetic setup failure")
                    }.apply {
                        raw.set(this)
                        connect(addr, timeout.toMillis().toInt())
                    }
                },
            )
            assertThatThrownBy { client.send(EgressRequest("GET", "http://stub.test:${server.localPort}/")) }
                .isInstanceOf(IOException::class.java).hasMessage("synthetic setup failure")
            assertThat(raw.get().isClosed).isTrue()
            assertThat(deadlines().queue).isEmpty()
        }
    }

    private fun stalledUpload(server: ServerSocket, anchors: KeyStore?, timeout: Duration) {
        val accepted = AtomicReference<Socket>()
        val raw = AtomicReference<Socket>()
        val connected = CountDownLatch(1)
        val drain = CountDownLatch(1)
        val peerFinished = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val peerFailure = AtomicReference<Throwable>()
        val peer = Thread.ofPlatform().daemon().start {
            try {
                server.accept().use { socket ->
                    accepted.set(socket)
                    readAfterDeadline(socket, connected, drain)
                }
            } catch (t: Throwable) {
                peerFailure.set(t)
            } finally {
                peerFinished.countDown()
            }
        }
        val client = deadlineClient(server.localPort, anchors, raw, timeout)
        val scheme = if (anchors == null) "http" else "https"
        val caller = Thread.ofPlatform().daemon().start {
            try {
                client.send(
                    EgressRequest("POST", "$scheme://stub.test:${server.localPort}/", body = ByteArray(BODY_BYTES)),
                )
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                finished.countDown()
            }
        }
        try {
            assertThat(connected.await(3, TimeUnit.SECONDS)).isTrue()
            assertThat(finished.await(3, TimeUnit.SECONDS)).isTrue()
            assertThat(failure.get()).isInstanceOf(IOException::class.java)
            assertThat(raw.get().isClosed).describedAs("closed by the call deadline, not test cleanup").isTrue()
            assertThat((raw.get() as UploadSocket).lingerWhenClosed).isEqualTo(-1)
            assertThat(deadlines().queue).isEmpty()
            drain.countDown()
            assertThat(peerFinished.await(3, TimeUnit.SECONDS)).isTrue()
            peerFailure.get()?.let {
                assertThat(it).isInstanceOf(IOException::class.java).isNotInstanceOf(SocketTimeoutException::class.java)
            }
        } finally {
            raw.get()?.close()
            drain.countDown()
            accepted.get()?.close()
            server.close()
            caller.join(2000)
            peer.join(2000)
            assertThat(caller.isAlive).isFalse()
            assertThat(peer.isAlive).isFalse()
        }
    }

    private fun readAfterDeadline(socket: Socket, connected: CountDownLatch, drain: CountDownLatch) {
        socket.soTimeout = 2000
        if (socket is SSLSocket) socket.startHandshake()
        connected.countDown()
        check(drain.await(5, TimeUnit.SECONDS))
        check(socket.getInputStream().readNBytes(BODY_BYTES + 4096).size < BODY_BYTES)
    }

    private fun deadlineClient(
        port: Int,
        anchors: KeyStore?,
        raw: AtomicReference<Socket>,
        timeout: Duration,
    ): SafeHttpClient {
        // Test-only reflection supplies a short deadline alongside real PKIX roots. Production
        // still exposes no trust-manager/SSLContext injection and gains no new public API.
        val constructor = SafeHttpClient::class.java.declaredConstructors.single {
            it.parameterTypes.size == 9 && it.parameterTypes[1] == KeyStore::class.java
        }.apply { isAccessible = true }
        val connector = EgressConnector { addr, connectTimeout ->
            UploadSocket().apply {
                sendBufferSize = 1024
                setSoLinger(true, 2)
                raw.set(this)
                connect(addr, connectTimeout.toMillis().toInt())
            }
        }
        return constructor.newInstance(
            policy(port), anchors, EgressResolver { listOf(loopback) }, Duration.ofSeconds(2),
            Duration.ofSeconds(2), SafeHttpClient.DEFAULT_MAX_RESPONSE_BYTES, connector, timeout, Duration.ofSeconds(1),
        ) as SafeHttpClient
    }

    private fun policy(port: Int): EgressPolicy = EgressPolicy.fromConfig(listOf("stub.test:$port;http;private"))

    private fun deadlines(): ScheduledThreadPoolExecutor = SafeHttpClient::class.java.getDeclaredField("CALL_DEADLINES")
        .apply { isAccessible = true }.get(null) as ScheduledThreadPoolExecutor

    private class UploadSocket : Socket() {
        @Volatile var lingerWhenClosed: Int? = null

        override fun close() {
            if (!isClosed) lingerWhenClosed = soLinger
            super.close()
        }
    }

    private companion object {
        const val BODY_BYTES = 1024 * 1024
    }
}
