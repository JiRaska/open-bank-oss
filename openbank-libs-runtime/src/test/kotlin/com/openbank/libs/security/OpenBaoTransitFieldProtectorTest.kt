// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.identity.BlindIndex
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpTimeoutException
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Drives [OpenBaoTransitFieldProtector] against an in-process stub of the Transit HTTP API that
 * behaves like the real engine on the points the adapter relies on: ciphertexts are bound to key
 * name and associated_data, carry the key version, and rewrap moves them to the latest version.
 */
class OpenBaoTransitFieldProtectorTest {

    private class StubTransit {
        data class Sealed(val key: String, val plaintext: String, val aad: String?)

        val mapper = ObjectMapper()
        val sealed = ConcurrentHashMap<String, Sealed>()
        val latestVersion = ConcurrentHashMap<String, Int>()
        val requests = mutableListOf<Pair<String, String>>()

        @Volatile var forcedStatus: Int? = null

        @Volatile var delayMillis: Long = 0
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newCachedThreadPool()
            createContext("/v1/transit/") { handle(it) }
            start()
        }
        val uri: URI get() = URI.create("http://127.0.0.1:${server.address.port}")

        fun keys(vararg names: String) = names.forEach { latestVersion[it] = 1 }

        private fun seal(key: String, plaintext: String, aad: String?): String {
            val ct = "vault:v${latestVersion.getValue(key)}:" +
                Base64.getEncoder().encodeToString(ByteArray(24).also(SecureRandom()::nextBytes))
            sealed[ct] = Sealed(key, plaintext, aad)
            return ct
        }

        private fun handle(ex: HttpExchange) {
            val body = ex.requestBody.readAllBytes().decodeToString()
            synchronized(requests) { requests += ex.requestURI.path to body }
            if (delayMillis > 0) Thread.sleep(delayMillis)
            forcedStatus?.let { return reply(ex, it, """{"errors":["forced"]}""") }
            if (ex.requestHeaders.getFirst("X-Vault-Token") !=
                TOKEN
            ) {
                return reply(ex, 403, """{"errors":["permission denied"]}""")
            }
            val segments = ex.requestURI.path.split("/")
            val op = segments[3]
            val key = segments[4]
            if (!latestVersion.containsKey(key)) return reply(ex, 400, """{"errors":["encryption key not found"]}""")
            val json = mapper.readTree(body)
            val aad = json["associated_data"]?.asText()
            val out: Map<String, Any> = when (op) {
                "encrypt" -> mapOf(
                    "ciphertext" to seal(key, json["plaintext"].asText(), aad),
                    "key_version" to latestVersion.getValue(key),
                )
                "decrypt", "rewrap" -> {
                    val s = sealed[json["ciphertext"].asText()]
                    if (s == null ||
                        s.key != key ||
                        s.aad != aad
                    ) {
                        return reply(ex, 400, """{"errors":["cipher: message authentication failed"]}""")
                    }
                    if (op ==
                        "decrypt"
                    ) {
                        mapOf("plaintext" to s.plaintext)
                    } else {
                        mapOf(
                            "ciphertext" to seal(key, s.plaintext, aad),
                        )
                    }
                }
                else -> return reply(ex, 404, "{}")
            }
            reply(ex, 200, mapper.writeValueAsString(mapOf("data" to out)))
        }

        private fun reply(ex: HttpExchange, status: Int, body: String) {
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
    }

    private lateinit var stub: StubTransit

    @BeforeEach fun start() {
        stub = StubTransit().also { it.keys("pan", "other") }
    }

    @AfterEach fun stop() = stub.server.stop(0)

    private fun protector(key: String = "pan", timeout: Duration = Duration.ofSeconds(5), token: String = TOKEN) =
        OpenBaoTransitFieldProtector(stub.uri, key, { token }, PEPPER, requestTimeout = timeout)

    @Test fun `encrypt then decrypt round-trips and yields a vault v1 ciphertext`() {
        val ct = protector().encrypt(SECRET)
        assertThat(ct).startsWith("vault:v1:")
        assertThat(ct).doesNotContain(SECRET.decodeToString())
        assertThat(protector().decrypt(ct)).isEqualTo(SECRET)
    }

    @Test fun `key version survives rotation and rewrap moves the value to the latest version`() {
        val v1 = protector().encrypt(SECRET)
        stub.latestVersion["pan"] = 2
        assertThat(TransitCiphertext.parse(v1).keyVersion).isEqualTo(1)
        assertThat(protector().decrypt(v1)).isEqualTo(SECRET)
        val v2 = protector().rewrap(v1)
        assertThat(TransitCiphertext.parse(v2).keyVersion).isEqualTo(2)
        assertThat(protector().decrypt(v2)).isEqualTo(SECRET)
        assertThat(protector().encrypt(SECRET)).startsWith("vault:v2:")
    }

    @Test fun `a ciphertext sealed under one key does not decrypt under another`() {
        val ct = protector("pan").encrypt(SECRET)
        assertThatThrownBy { protector("other").decrypt(ct) }
            .isInstanceOf(FieldProtectionException::class.java)
            .hasMessageContaining("HTTP 400")
    }

    @Test fun `associated data is bound - a different or missing aad fails`() {
        val ct = protector().encrypt(SECRET, "card:1".toByteArray())
        assertThat(protector().decrypt(ct, "card:1".toByteArray())).isEqualTo(SECRET)
        assertThatThrownBy {
            protector().decrypt(ct, "card:2".toByteArray())
        }.isInstanceOf(FieldProtectionException::class.java)
        assertThatThrownBy { protector().decrypt(ct) }.isInstanceOf(FieldProtectionException::class.java)
        val sent = stub.requests.first { it.first.endsWith("/encrypt/pan") }.second
        assertThat(ObjectMapper().readTree(sent)["associated_data"].asText())
            .isEqualTo(Base64.getEncoder().encodeToString("card:1".toByteArray()))
    }

    @Test fun `a slow Transit times out and fails closed`() {
        stub.delayMillis = 1_500
        assertThatThrownBy { protector(timeout = Duration.ofMillis(200)).encrypt(SECRET) }
            .isInstanceOf(FieldProtectionException::class.java)
            .hasCauseInstanceOf(HttpTimeoutException::class.java)
    }

    @Test fun `5xx fails closed on every operation and never echoes the input`() {
        val ct = protector().encrypt(SECRET)
        stub.forcedStatus = 503
        listOf<() -> Any>({
            protector().encrypt(SECRET)
        }, { protector().decrypt(ct) }, { protector().rewrap(ct) }).forEach { op ->
            assertThatThrownBy { op() }
                .isInstanceOf(FieldProtectionException::class.java)
                .hasMessageContaining("HTTP 503")
                .satisfies({ assertThat(it.message).doesNotContain(ct).doesNotContain(TOKEN) })
        }
    }

    @Test fun `a rejected token fails closed`() {
        assertThatThrownBy { protector(token = "wrong").encrypt(SECRET) }
            .isInstanceOf(FieldProtectionException::class.java).hasMessageContaining("HTTP 403")
    }

    @Test fun `unreachable Transit fails closed`() {
        val dead = stub.uri
        stub.server.stop(0)
        assertThatThrownBy { OpenBaoTransitFieldProtector(dead, "pan", { TOKEN }, PEPPER).encrypt(SECRET) }
            .isInstanceOf(FieldProtectionException::class.java)
    }

    @Test fun `malformed ciphertext is rejected before any network call and not echoed`() {
        assertThatThrownBy { protector().decrypt("plaintext-4111111111111111") }
            .isInstanceOf(FieldProtectionException::class.java)
            .satisfies({ assertThat(it.message).doesNotContain("4111") })
        assertThat(stub.requests).isEmpty()
    }

    @Test fun `tokenize is the ADR-0189 blind index, deterministic and pepper-bound`() {
        assertThat(protector().tokenize("4111111111111111")).isEqualTo(BlindIndex.compute(PEPPER, "4111111111111111"))
        assertThat(
            OpenBaoTransitFieldProtector(stub.uri, "pan", {
                TOKEN
            }, "other".toByteArray()).tokenize("4111111111111111"),
        )
            .isNotEqualTo(protector().tokenize("4111111111111111"))
        assertThat(stub.requests).isEmpty()
    }

    @Test fun `key name cannot inject a path`() {
        assertThatThrownBy { OpenBaoTransitFieldProtector(stub.uri, "../sys", { TOKEN }, PEPPER) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private companion object {
        const val TOKEN = "s.test-token"
        val PEPPER = "test-pepper".toByteArray()
        val SECRET = "4111111111111111".toByteArray()
    }
}
