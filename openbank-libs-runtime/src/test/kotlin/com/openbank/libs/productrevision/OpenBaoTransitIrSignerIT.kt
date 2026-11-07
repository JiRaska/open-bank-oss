// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.productrevision

import com.openbank.libs.security.OpenBaoTransitFieldProtectorIT
import com.openbank.libs.security.OpenBaoTransport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
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
import java.util.UUID

/**
 * [OpenBaoTransitIrSigner] against a REAL OpenBao (dev mode): the evidence the echoing stub in
 * [OpenBaoTransitIrSignerTest] cannot give (review finding M2) — that Transit accepts the request
 * shape for an asymmetric key, that its signature verifies, and that a tampered signature, a
 * signature from another key and a missing key are each refused by the server, not by the stub.
 *
 * Docker missing: SKIPPED locally, but FAILS when `CI=true`, like [OpenBaoTransitFieldProtectorIT].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenBaoTransitIrSignerIT {

    private lateinit var bao: GenericContainer<*>
    private lateinit var transport: OpenBaoTransport
    private val http = HttpClient.newHttpClient()

    @BeforeAll
    fun start() {
        OpenBaoTransitFieldProtectorIT.requireDocker(
            DockerClientFactory.instance().isDockerAvailable,
            System.getenv("CI"),
        )
        bao = GenericContainer(DockerImageName.parse(IMAGE))
            .withEnv("BAO_DEV_ROOT_TOKEN_ID", ROOT)
            .withCommand("server", "-dev", "-dev-listen-address=0.0.0.0:8200")
            .withExposedPorts(PORT)
            .waitingFor(Wait.forHttp("/v1/sys/health").forStatusCode(200))
        bao.start()
        transport =
            OpenBaoTransport(
                URI.create("http://${bao.host}:${bao.getMappedPort(PORT)}"),
                allowInsecureHttpForTests = true,
            )
        admin("v1/sys/mounts/transit", """{"type":"transit"}""")
        admin("v1/transit/keys/product-ir", """{"type":"ed25519"}""")
        admin("v1/transit/keys/other-ir", """{"type":"ed25519"}""")
        admin("v1/transit/keys/product-ir-p256", """{"type":"ecdsa-p256"}""")
    }

    @AfterAll
    fun stop() {
        if (::bao.isInitialized) bao.stop()
    }

    private fun admin(path: String, body: String) {
        val res = http.send(
            HttpRequest.newBuilder(transport.uri(path)).header("X-Vault-Token", ROOT)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(res.statusCode() in 200..299) { "setup $path failed: HTTP ${res.statusCode()} ${res.body()}" }
    }

    private fun signer(key: String) = OpenBaoTransitIrSigner(transport, key, { ROOT })

    @Test
    fun `an ed25519 signature over the revision hash verifies on the real server`() {
        val sig = signer("product-ir").sign(ID)
        assertThat(sig.signature).startsWith("vault:v1:")
        assertThat(signer("product-ir").verify(ID, sig)).isTrue()
    }

    @Test
    fun `an ecdsa-p256 key honours hash_algorithm and verifies too`() {
        val sig = signer("product-ir-p256").sign(ID)
        assertThat(signer("product-ir-p256").verify(ID, sig)).isTrue()
        assertThat(signer("product-ir-p256").verify(ID.copy(revisionHash = OTHER_HASH), sig)).isFalse()
    }

    @Test
    fun `the server refuses a signature over another hash, a tampered one and one made by another key`() {
        val s = signer("product-ir")
        val sig = s.sign(ID)
        assertThat(s.verify(ID.copy(revisionHash = OTHER_HASH), sig)).isFalse()
        val body = sig.signature.removePrefix("vault:v1:")
        val flipped = (if (body[0] == 'A') "B" else "A") + body.substring(1)
        assertThat(s.verify(ID, sig.copy(signature = "vault:v1:$flipped"))).isFalse()
        // Made by another key, then presented under this key's id: only the SERVER can refuse this.
        val foreign = signer("other-ir").sign(ID)
        assertThat(s.verify(ID, sig.copy(signature = foreign.signature))).isFalse()
    }

    @Test
    fun `signing with a key that does not exist fails closed instead of creating one`() {
        assertThatThrownBy { signer("no-such-key").sign(ID) }
            .isInstanceOf(IrSigningException::class.java)
            .hasMessageContaining("no-such-key")
    }

    companion object {
        private const val IMAGE = "openbao/openbao:2.5.4"
        private const val PORT = 8200
        private const val ROOT = "it-root-token"
        private const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        private const val OTHER_HASH = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
        private val ID =
            RevisionIdentity("SAV-FIXED", UUID.fromString("00000000-0000-0000-0000-0000000000a1"), 1, 1, HASH)
    }
}
