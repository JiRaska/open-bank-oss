// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.erasure

import com.openbank.analytics.infrastructure.support.KGenericContainer
import com.openbank.libs.analytics.AggregateKey
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * End-to-end verification of [VaultCryptoErasure] (ADR-0023 F6 — GDPR Art. 17 crypto-shredding)
 * against a real Vault server running the Transit engine. The unit test stubs the
 * [VaultCryptoErasure.vaultRequest] seam; this drives the **actual** two-step Vault REST flow
 * (`POST .../config {"deletion_allowed":true}` → `DELETE .../keys/{name}`) and proves:
 *  - a real Transit key is actually destroyed (subsequent reads 404), and
 *  - a second erase of the now-absent key is an idempotent no-op (returns 0) — the property that
 *    makes GDPR erasure safe to retry.
 *
 * Self-skips when Docker is absent, so the offline build is unaffected.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class VaultCryptoErasureIT {

    companion object {
        private const val ROOT_TOKEN = "root-token-it"

        @Container
        @JvmStatic
        private val vault: KGenericContainer =
            KGenericContainer("hashicorp/vault:1.15.6")
                // Bypass the image's docker-entrypoint.sh: on some Docker hosts it hangs forever on
                // `setcap cap_ipc_lock=+ep /bin/vault` (the mlock capability grant), so the server
                // never boots and the container fails its startup probe. Exec the vault binary
                // directly — dev mode doesn't need mlock — which comes up in a few seconds.
                .withCreateContainerCmdModifier { it.withEntrypoint("vault") }
                .withCommand("server", "-dev", "-dev-root-token-id=$ROOT_TOKEN", "-dev-listen-address=0.0.0.0:8200")
                .withExposedPorts(8200)
                .waitingFor(
                    Wait.forHttp("/v1/sys/health").forPort(8200).forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(3))
                )

        private fun baseUrl() = "http://${vault.host}:${vault.getMappedPort(8200)}"

        @BeforeAll
        @JvmStatic
        fun awaitToken() {
            // /sys/health can answer 200 during the brief window where dev mode has unsealed but not yet
            // swapped in the configured -dev-root-token-id (it revokes the auto-generated root first), so
            // an early request would 403. Poll an authenticated endpoint until ROOT_TOKEN is live.
            val client = HttpClient.newHttpClient()
            val deadline = System.currentTimeMillis() + 30_000
            while (true) {
                val ok = runCatching {
                    val req = HttpRequest.newBuilder(URI.create("${baseUrl()}/v1/auth/token/lookup-self"))
                        .header("X-Vault-Token", ROOT_TOKEN).GET().build()
                    client.send(req, HttpResponse.BodyHandlers.ofString()).statusCode() == 200
                }.getOrDefault(false)
                if (ok) return
                check(System.currentTimeMillis() < deadline) { "vault root token never became valid" }
                Thread.sleep(250)
            }
        }
    }

    private val http: HttpClient = HttpClient.newHttpClient()

    private fun vault(method: String, path: String, body: String?): HttpResponse<String> {
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody()
        else HttpRequest.BodyPublishers.ofString(body)
        val req = HttpRequest.newBuilder(URI.create("${baseUrl()}/v1/$path"))
            .header("X-Vault-Token", ROOT_TOKEN)
            .header("Content-Type", "application/json")
            .method(method, publisher)
            .build()
        return http.send(req, HttpResponse.BodyHandlers.ofString())
    }

    private fun adapter() = VaultCryptoErasure().apply {
        url = baseUrl(); token = ROOT_TOKEN; mount = "transit"; keyPrefix = "analytics"
    }

    @Test
    fun `erase destroys a real Transit key and a repeat is an idempotent no-op`() = runBlocking<Unit> {
        // Enable the Transit secrets engine (mirrors docker/vault/init/init.sh).
        assertThat(vault("POST", "sys/mounts/transit", """{"type":"transit"}""").statusCode()).isIn(200, 204)

        val key = AggregateKey("PARTY", "party-erase-me")
        val erasure = adapter()
        val name = erasure.keyName(key)

        // Provision the per-subject key, then prove it exists.
        assertThat(vault("POST", "transit/keys/$name", null).statusCode()).isIn(200, 204)
        assertThat(vault("GET", "transit/keys/$name", null).statusCode()).isEqualTo(200)

        // First erase: the key is destroyed → the ciphertext in bronze becomes permanently unreadable.
        assertThat(erasure.erase(key)).isEqualTo(1)
        assertThat(vault("GET", "transit/keys/$name", null).statusCode()).isEqualTo(404)

        // Second erase of the now-absent key: idempotent no-op (GDPR erasure must be safe to retry).
        assertThat(erasure.erase(key)).isEqualTo(0)
    }

    @Test
    fun `erase of a never-existing key is a no-op`() = runBlocking<Unit> {
        // No transit/keys created for this aggregate → config POST 404s → returns 0 without throwing.
        assertThat(vault("POST", "sys/mounts/transit", """{"type":"transit"}""").statusCode()).isIn(200, 204, 400)
        val erasure = adapter()
        assertThat(erasure.erase(AggregateKey("ACCOUNT", "never-existed"))).isEqualTo(0)
    }
}
