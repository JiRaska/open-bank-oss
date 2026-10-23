// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.productrevision

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.security.OpenBaoTokenSource
import com.openbank.libs.security.OpenBaoTransport
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/** Stub Transit: `sign` returns `vault:v1:<base64(input)>`, `verify` compares; rejects a wrong token with 403. */
class OpenBaoTransitIrSignerTest {

    private val mapper = ObjectMapper()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val forbidden = AtomicInteger(0)
    private var status = 200
    private var delayMs = 0L
    private val requests = mutableListOf<String>()

    @BeforeEach
    fun start() {
        server.createContext("/") { ex -> handle(ex) }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.path
        val body = mapper.readTree(ex.requestBody.readBytes())
        requests += "$path ${ex.requestHeaders.getFirst("X-Vault-Token")} $body"
        if (delayMs > 0) Thread.sleep(delayMs)
        if (ex.requestHeaders.getFirst("X-Vault-Token") != GOOD_TOKEN) {
            forbidden.incrementAndGet()
            return reply(ex, 403, """{"errors":["permission denied"]}""")
        }
        if (status != 200) return reply(ex, status, """{"errors":["boom"]}""")
        val input = body["input"].asText()
        when {
            path.endsWith("/sign/product-ir") -> reply(ex, 200, """{"data":{"signature":"vault:v1:$input"}}""")
            path.endsWith("/verify/product-ir") -> {
                val valid = body["signature"].asText() == "vault:v1:$input"
                reply(ex, 200, """{"data":{"valid":$valid}}""")
            }
            else -> reply(ex, 404, """{"errors":["no handler"]}""")
        }
    }

    private fun reply(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray()
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun transport(timeout: Duration = Duration.ofSeconds(5)) = OpenBaoTransport(
        URI.create("http://127.0.0.1:${server.address.port}/"),
        requestTimeout = timeout,
        allowInsecureHttpForTests = true,
    )

    private class Tokens(vararg tokens: String) : OpenBaoTokenSource {
        private val queue = ArrayDeque(tokens.toList())
        val invalidated = mutableListOf<String>()
        override fun token(): String = queue.firstOrNull() ?: error("no tokens left")
        override fun invalidate(rejected: String) {
            invalidated += rejected
            queue.removeFirst()
        }
    }

    private fun signer(tokens: OpenBaoTokenSource = Tokens(GOOD_TOKEN), timeout: Duration = Duration.ofSeconds(5)) =
        OpenBaoTransitIrSigner(transport(timeout), "product-ir", tokens)

    @Test
    fun `sign produces a transit signature over the hash bytes and verify accepts it`() {
        val s = signer()
        val sig = s.sign(HASH)
        assertThat(sig.algorithm).isEqualTo(OpenBaoTransitIrSigner.ALGORITHM)
        assertThat(sig.keyId).isEqualTo("product-ir")
        assertThat(sig.signature).isEqualTo("vault:v1:" + Base64.getEncoder().encodeToString(HASH.toByteArray()))
        assertThat(requests.single()).contains("/v1/transit/sign/product-ir", "\"hash_algorithm\":\"sha2-256\"")
        assertThat(s.verify(HASH, sig)).isTrue()
    }

    @Test
    fun `verify is false for another hash, key id or algorithm, and never calls Transit for a malformed signature`() {
        val s = signer()
        val sig = s.sign(HASH)
        assertThat(s.verify("b".repeat(64), sig)).isFalse()
        requests.clear()
        assertThat(s.verify(HASH, sig.copy(keyId = "other"))).isFalse()
        assertThat(s.verify(HASH, sig.copy(algorithm = HmacIrSigner.ALGORITHM))).isFalse()
        assertThat(s.verify(HASH, sig.copy(signature = "garbage"))).isFalse()
        assertThat(requests).isEmpty()
    }

    @Test
    fun `a 403 invalidates the token and retries once, a second 403 fails closed`() {
        val tokens = Tokens("stale", GOOD_TOKEN)
        assertThat(signer(tokens).sign(HASH).signature).startsWith("vault:v1:")
        assertThat(tokens.invalidated).containsExactly("stale")
        assertThat(forbidden.get()).isEqualTo(1)

        assertThatThrownBy { signer(Tokens("bad", "worse", "worst")).sign(HASH) }
            .isInstanceOf(IrSigningException::class.java)
            .hasMessageContaining("HTTP 403")
    }

    @Test
    fun `a 5xx, a timeout and a verdict-less response are unavailability, not a false verdict`() {
        val s = signer()
        val sig = s.sign(HASH)
        status = 500
        assertThatThrownBy {
            s.verify(HASH, sig)
        }.isInstanceOf(IrSigningException::class.java).hasMessageContaining("HTTP 500")
        assertThatThrownBy { s.sign(HASH) }.isInstanceOf(IrSigningException::class.java)
        status = 200
        delayMs = 400
        assertThatThrownBy { signer(timeout = Duration.ofMillis(50)).sign(HASH) }
            .isInstanceOf(IrSigningException::class.java)
            .hasMessageContaining("HttpTimeoutException")
    }

    @Test
    fun `refuses a non-hash input before any network call`() {
        assertThatThrownBy { signer().sign("hello") }.isInstanceOf(IrSigningException::class.java)
        assertThat(requests).isEmpty()
    }

    companion object {
        private const val GOOD_TOKEN = "s.good"
        private const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
