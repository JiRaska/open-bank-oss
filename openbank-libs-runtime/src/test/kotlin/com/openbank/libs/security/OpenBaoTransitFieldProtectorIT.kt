// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.abort
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/**
 * [OpenBaoTransitFieldProtector] against a REAL OpenBao (dev mode) — the evidence the stub-based
 * test cannot give: that the server honours `associated_data`, how `rewrap` treats it, and that an
 * `update`-only policy cannot upsert a key.
 *
 * Docker missing: SKIPPED locally, but FAILS when `CI=true` — a CI lane that silently skipped this
 * would report the stub as the only evidence again.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenBaoTransitFieldProtectorIT {

    private lateinit var bao: GenericContainer<*>
    private lateinit var transport: OpenBaoTransport
    private val http = HttpClient.newHttpClient()
    private val mapper = ObjectMapper()

    @BeforeAll fun start() {
        if (!DockerClientFactory.instance().isDockerAvailable) {
            if (System.getenv("CI") == "true") fail<Unit>("Docker is required for OpenBaoTransitFieldProtectorIT in CI")
            abort<Unit>("Docker not available — skipping OpenBao IT")
        }
        bao = GenericContainer(DockerImageName.parse(IMAGE))
            .withEnv("BAO_DEV_ROOT_TOKEN_ID", ROOT)
            .withCommand("server", "-dev", "-dev-listen-address=0.0.0.0:8200")
            .withExposedPorts(PORT)
            .waitingFor(Wait.forHttp("/v1/sys/health").forStatusCode(200))
        bao.start()
        val addr = URI.create("http://${bao.host}:${bao.getMappedPort(PORT)}")
        transport = OpenBaoTransport(addr, allowInsecureHttpForTests = true)
        admin("POST", "v1/sys/mounts/transit", """{"type":"transit"}""")
        admin("POST", "v1/transit/keys/pan", """{"type":"aes256-gcm96"}""")
        admin("POST", "v1/transit/keys/rot", """{"type":"aes256-gcm96"}""")
        admin("POST", "v1/transit/keys/derived", """{"type":"aes256-gcm96","derived":true}""")
        admin("POST", "v1/transit/keys/rsa", """{"type":"rsa-2048"}""")
    }

    @AfterAll fun stop() {
        if (::bao.isInitialized) bao.stop()
    }

    private fun admin(method: String, path: String, body: String? = null): HttpResponse<String> {
        val req = HttpRequest.newBuilder(transport.uri(path)).header("X-Vault-Token", ROOT)
            .method(
                method,
                body?.let {
                    HttpRequest.BodyPublishers.ofString(it)
                } ?: HttpRequest.BodyPublishers.noBody(),
            )
            .build()
        return http.send(req, HttpResponse.BodyHandlers.ofString())
    }

    private fun protector(key: String = "pan", token: String = ROOT) =
        OpenBaoTransitFieldProtector(transport, key, { token }, "pepper".toByteArray())

    private val aad = "card:1".toByteArray()

    @Test fun `encrypt and decrypt round-trip against the real server`() {
        val ct = protector().encrypt(SECRET)
        assertThat(ct).startsWith("vault:v1:")
        assertThat(protector().decrypt(ct)).isEqualTo(SECRET)
    }

    @Test fun `wrong or missing associated data fails on the real server`() {
        val ct = protector().encrypt(SECRET, aad)
        assertThat(protector().decrypt(ct, aad)).isEqualTo(SECRET)
        assertThatThrownBy { protector().decrypt(ct, "card:2".toByteArray()) }
            .isInstanceOf(FieldProtectionException::class.java).hasMessageContaining("HTTP 400")
        assertThatThrownBy { protector().decrypt(ct) }
            .isInstanceOf(FieldProtectionException::class.java).hasMessageContaining("HTTP 400")
    }

    @Test fun `server-side rewrap cannot carry associated data, so the protector re-encrypts`() {
        val ct = protector("rot").encrypt(SECRET, aad)
        admin("POST", "v1/transit/keys/rot/rotate")
        // The premise, measured: OpenBao's rewrap rejects an AAD-bound ciphertext with or without
        // an associated_data field.
        listOf(
            """{"ciphertext":"$ct"}""",
            """{"ciphertext":"$ct","associated_data":"${Base64.getEncoder().encodeToString(aad)}"}""",
        ).forEach { body ->
            assertThat(admin("POST", "v1/transit/rewrap/rot", body).statusCode()).isEqualTo(400)
        }
        val v2 = protector("rot").rewrap(ct, aad)
        assertThat(TransitCiphertext.parse(v2).keyVersion).isEqualTo(2)
        assertThat(protector("rot").decrypt(v2, aad)).isEqualTo(SECRET)
        assertThatThrownBy { protector("rot").decrypt(v2) }.isInstanceOf(FieldProtectionException::class.java)
    }

    @Test fun `key version survives rotation and server rewrap moves an unbound value forward`() {
        val key = "ver"
        admin("POST", "v1/transit/keys/$key", """{"type":"aes256-gcm96"}""")
        val v1 = protector(key).encrypt(SECRET)
        admin("POST", "v1/transit/keys/$key/rotate")
        assertThat(TransitCiphertext.parse(v1).keyVersion).isEqualTo(1)
        assertThat(protector(key).decrypt(v1)).isEqualTo(SECRET)
        assertThat(protector(key).encrypt(SECRET)).startsWith("vault:v2:")
        val rewrapped = protector(key).rewrap(v1)
        assertThat(TransitCiphertext.parse(rewrapped).keyVersion).isEqualTo(2)
        assertThat(protector(key).decrypt(rewrapped)).isEqualTo(SECRET)
    }

    @Test fun `an update-only policy cannot auto-create a key through encrypt`() {
        admin(
            "PUT",
            "v1/sys/policies/acl/field-protection",
            mapper.writeValueAsString(
                mapOf(
                    "policy" to """
                        path "transit/encrypt/*" { capabilities = ["update"] }
                        path "transit/decrypt/*" { capabilities = ["update"] }
                    """.trimIndent(),
                ),
            ),
        )
        val token = mapper.readTree(
            admin("POST", "v1/auth/token/create", """{"policies":["field-protection"]}""").body(),
        )["auth"]["client_token"].asText()
        assertThat(protector(token = token).decrypt(protector(token = token).encrypt(SECRET))).isEqualTo(SECRET)
        assertThatThrownBy { protector("typo-key", token).encrypt(SECRET) }
            .isInstanceOf(FieldProtectionException::class.java).hasMessageContaining("HTTP 403")
        assertThat(admin("GET", "v1/transit/keys/typo-key").statusCode()).isEqualTo(404)
    }

    @Test fun `verifyKey accepts a non-derived AEAD key and rejects derived or non-AEAD keys`() {
        protector("pan").verifyKey()
        assertThatThrownBy { protector("derived").verifyKey() }
            .isInstanceOf(FieldProtectionException::class.java).hasMessageContaining("derived")
        assertThatThrownBy { protector("rsa").verifyKey() }
            .isInstanceOf(FieldProtectionException::class.java).hasMessageContaining("unsupported type")
        assertThatThrownBy { protector("absent").verifyKey() }
            .isInstanceOf(FieldProtectionException::class.java)
    }

    private companion object {
        const val IMAGE = "openbao/openbao:2.5.4"
        const val PORT = 8200
        const val ROOT = "it-root-token"
        val SECRET = "4111111111111111".toByteArray()
    }
}
