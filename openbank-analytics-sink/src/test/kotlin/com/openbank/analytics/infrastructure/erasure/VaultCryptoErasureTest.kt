// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.erasure

import com.openbank.libs.analytics.AggregateKey
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

/**
 * Plain-JUnit tests for the Vault-Transit crypto-shred adapter. The HTTP seam is overridden to script
 * Vault responses, so the two-step (allow-deletion → destroy) flow and idempotency are verified
 * without a Vault server.
 */
class VaultCryptoErasureTest {

    /** Records every Vault call and replays scripted (status, body) responses keyed by "METHOD path". */
    private class ScriptedVault(private val responses: Map<String, Pair<Int, String>>) : VaultCryptoErasure() {
        val calls = mutableListOf<String>()

        init {
            url = "http://vault:8200"
            token = Optional.of("test-token")
            mount = "transit"
            keyPrefix = "analytics"
        }

        override suspend fun vaultRequest(method: String, path: String, body: String?): Pair<Int, String> {
            val k = "$method $path"
            calls += k
            return responses[k] ?: (404 to "")
        }
    }

    @Test
    fun `key name is sanitised and collision-safe`() {
        val v = ScriptedVault(emptyMap())

        assertThat(v.keyName(AggregateKey("ACCOUNT", "acc-1"))).isEqualTo("analytics-account-acc-1")
        // Unsafe characters become '-'.
        assertThat(v.keyName(AggregateKey("Party/X", "id with space"))).isEqualTo("analytics-party-x-id-with-space")
    }

    @Test
    fun `erase allows deletion then destroys the key and reports one`() = runBlocking<Unit> {
        val v = ScriptedVault(
            mapOf(
                "POST transit/keys/analytics-account-acc-1/config" to (204 to ""),
                "DELETE transit/keys/analytics-account-acc-1" to (204 to ""),
            ),
        )

        val affected = v.erase(AggregateKey("ACCOUNT", "acc-1"))

        assertThat(affected).isEqualTo(1)
        assertThat(v.calls).containsExactly(
            "POST transit/keys/analytics-account-acc-1/config",
            "DELETE transit/keys/analytics-account-acc-1",
        )
    }

    @Test
    fun `erase is an idempotent no-op when the key is already gone`() = runBlocking<Unit> {
        val v = ScriptedVault(
            mapOf("POST transit/keys/analytics-account-acc-1/config" to (404 to "not found")),
        )

        val affected = v.erase(AggregateKey("ACCOUNT", "acc-1"))

        assertThat(affected).isEqualTo(0)
        // It must NOT attempt the destroy when the key never existed.
        assertThat(v.calls).containsExactly("POST transit/keys/analytics-account-acc-1/config")
    }

    @Test
    fun `erase throws on an unexpected Vault error so the failure is visible`() {
        val v = ScriptedVault(
            mapOf("POST transit/keys/analytics-account-acc-1/config" to (500 to "boom")),
        )

        runCatching { runBlocking { v.erase(AggregateKey("ACCOUNT", "acc-1")) } }
            .also { assertThat(it.isFailure).isTrue() }
    }
}
