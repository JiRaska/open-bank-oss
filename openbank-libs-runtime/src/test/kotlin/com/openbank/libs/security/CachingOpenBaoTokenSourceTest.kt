// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CachingOpenBaoTokenSourceTest {

    private class MutableClock(var now: Instant = Instant.parse("2026-09-27T00:00:00Z")) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?) = this

        override fun instant(): Instant = now
    }

    private val logins = AtomicInteger()
    private val clock = MutableClock()

    private fun source(lease: Duration = Duration.ofMinutes(10)) =
        CachingOpenBaoTokenSource({ OpenBaoToken("t${logins.incrementAndGet()}", lease) }, clock)

    @Test fun `N calls within the lease cost one login`() {
        val s = source()
        repeat(100) { assertThat(s.token()).isEqualTo("t1") }
        assertThat(logins.get()).isEqualTo(1)
    }

    @Test fun `a token nearing expiry is refreshed before it lapses`() {
        val s = source(Duration.ofMinutes(10))
        s.token()
        clock.now = clock.now.plus(Duration.ofMinutes(8))
        assertThat(s.token()).isEqualTo("t1")
        clock.now = clock.now.plus(Duration.ofSeconds(61))
        assertThat(s.token()).isEqualTo("t2")
        assertThat(logins.get()).isEqualTo(2)
    }

    @Test fun `invalidate drops only the rejected token`() {
        val s = source()
        s.token()
        s.invalidate("not-the-cached-one")
        assertThat(s.token()).isEqualTo("t1")
        s.invalidate("t1")
        assertThat(s.token()).isEqualTo("t2")
    }

    @Test fun `concurrent callers share one in-flight login`() {
        val gate = CountDownLatch(1)
        val s = CachingOpenBaoTokenSource({
            gate.await(5, TimeUnit.SECONDS)
            OpenBaoToken("t${logins.incrementAndGet()}", Duration.ofMinutes(10))
        }, clock)
        val pool = Executors.newFixedThreadPool(16)
        val futures = (1..16).map { pool.submit<String> { s.token() } }
        Thread.sleep(200)
        gate.countDown()
        assertThat(futures.map { it.get(5, TimeUnit.SECONDS) }).containsOnly("t1")
        assertThat(logins.get()).isEqualTo(1)
        pool.shutdownNow()
    }

    @Test fun `a failed login is not cached and fails closed`() {
        val s = CachingOpenBaoTokenSource({
            logins.incrementAndGet()
            throw FieldProtectionException("login failed: HTTP 403")
        }, clock)
        repeat(2) { assertThatThrownBy { s.token() }.isInstanceOf(FieldProtectionException::class.java) }
        assertThat(logins.get()).isEqualTo(2)
    }

    @Test fun `end to end - protector plus kubernetes login logs in once, and once more on a 403`(@TempDir dir: Path) {
        val jwt = dir.resolve("token").also { Files.writeString(it, "sa-jwt") }
        val loginCalls = AtomicInteger()
        var valid = "tok-1"
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/auth/k8s-prod/login") { ex ->
                val n = loginCalls.incrementAndGet()
                valid = "tok-$n"
                val body = """{"auth":{"client_token":"tok-$n","lease_duration":3600}}""".toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            createContext("/v1/transit/encrypt/pan") { ex ->
                ex.requestBody.readAllBytes()
                val ok = ex.requestHeaders.getFirst("X-Vault-Token") == valid
                val reply = if (ok) """{"data":{"ciphertext":"vault:v1:AAAA"}}""" else """{"errors":[]}"""
                val body = reply.toByteArray()
                ex.sendResponseHeaders(if (ok) 200 else 403, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val transport = OpenBaoTransport(
                URI.create("http://127.0.0.1:${server.address.port}"),
                allowInsecureHttpForTests = true,
            )
            val login = OpenBaoKubernetesLogin(transport, "svc", jwt, authMount = "k8s-prod")
            val p =
                OpenBaoTransitFieldProtector(
                    transport,
                    "pan",
                    CachingOpenBaoTokenSource(login::login),
                    "p".toByteArray(),
                )
            repeat(10) { p.encrypt("x".toByteArray()) }
            assertThat(loginCalls.get()).isEqualTo(1)
            valid = "revoked"
            p.encrypt("x".toByteArray())
            assertThat(loginCalls.get()).isEqualTo(2)
        } finally {
            server.stop(0)
        }
    }

    @Test fun `kubernetes login refuses plain http and a malformed mount`(@TempDir dir: Path) {
        assertThatThrownBy { OpenBaoTransport(URI.create("http://bao:8200")) }
            .isInstanceOf(IllegalArgumentException::class.java)
        val t = OpenBaoTransport(URI.create("https://bao:8200"))
        assertThatThrownBy { OpenBaoKubernetesLogin(t, "r", dir, authMount = "../sys") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
