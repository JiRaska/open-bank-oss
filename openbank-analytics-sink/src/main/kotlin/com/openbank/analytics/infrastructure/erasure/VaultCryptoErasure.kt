// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.erasure

import com.openbank.analytics.application.port.out.CryptoErasure
import com.openbank.libs.analytics.AggregateKey
import io.quarkus.arc.properties.IfBuildProperty
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/**
 * Vault-Transit-backed [CryptoErasure] (ADR-0023, F6) — GDPR Art. 17 crypto-shredding for the 10-year
 * warehouse.
 *
 * Crypto-shredding model: each data subject's erasable analytics fields are encrypted under a
 * **per-subject Vault Transit key**; "erasure" destroys that key, so the ciphertext that remains in
 * the immutable bronze log becomes permanently unreadable — without mutating or deleting the log of
 * record (which would break the tamper-evidence chain / accounting hold). This is the standard way to
 * reconcile "right to erasure" with "immutable regulatory log".
 *
 * Vault deletes a Transit key only when deletion is explicitly allowed, so erasure is two steps:
 *   1. `POST {mount}/keys/{name}/config {"deletion_allowed":true}`
 *   2. `DELETE {mount}/keys/{name}`
 * A missing key (404) means already-shredded → idempotent no-op (returns 0).
 *
 * Why Vault and not AWS KMS: Vault is already provisioned in `openbank-infra` (dev), so this adapter
 * is runnable end-to-end there once the Transit engine is enabled (see `docker/openbao/init/init.sh`).
 * It is the `@Alternative @Priority(100)` binding behind the `@Default`
 * [NoOpCryptoErasure], gated at build time by `openbank.analytics.erasure.backend=vault`, so the
 * default profile keeps the offline no-op. Key-name derivation is pure and unit-tested; the HTTP calls
 * go through an overridable seam so tests need no Vault server.
 */
@ApplicationScoped
@Alternative
@Priority(100)
@IfBuildProperty(name = "openbank.analytics.erasure.backend", stringValue = "vault")
open class VaultCryptoErasure : CryptoErasure {

    @ConfigProperty(name = "openbank.analytics.vault.url", defaultValue = "http://localhost:8200")
    lateinit var url: String

    @ConfigProperty(name = "openbank.analytics.vault.token", defaultValue = "")
    lateinit var token: String

    @ConfigProperty(name = "openbank.analytics.vault.transit-mount", defaultValue = "transit")
    lateinit var mount: String

    @ConfigProperty(name = "openbank.analytics.vault.key-prefix", defaultValue = "analytics")
    lateinit var keyPrefix: String

    private val log = Logger.getLogger(VaultCryptoErasure::class.java)
    private val http: HttpClient by lazy { HttpClient.newHttpClient() }

    override suspend fun erase(key: AggregateKey): Long {
        val name = keyName(key)
        // Step 1: allow deletion. An absent key means it was already shredded → idempotent no-op.
        // Vault signals "no such key" two ways: a 404, or (observed on Transit ≥1.15) a 400 whose body
        // reads "no existing key named ... could be found". Treat both as the no-op so GDPR erasure
        // stays safe to retry; any *other* 400 (malformed request) must still surface as an error.
        val (cfgStatus, cfgBody) = vaultRequest("POST", "$mount/keys/$name/config", """{"deletion_allowed":true}""")
        if (cfgStatus == 404 || (cfgStatus == 400 && cfgBody.contains("no existing key", ignoreCase = true))) {
            log.infof("crypto-shred no-op: vault transit key '%s' absent (already erased)", name)
            return 0
        }
        if (cfgStatus !in 200..299) error("Vault config failed for key '$name': HTTP $cfgStatus ${cfgBody.take(300)}")

        // Step 2: destroy the key — all ciphertext encrypted under it becomes unrecoverable.
        val (delStatus, body) = vaultRequest("DELETE", "$mount/keys/$name", null)
        return when {
            delStatus in 200..299 -> { log.infof("crypto-shred: destroyed vault transit key '%s'", name); 1 }
            delStatus == 404 -> 0
            else -> error("Vault key destruction failed for '$name': HTTP $delStatus ${body.take(300)}")
        }
    }

    /**
     * Deterministic, collision-safe Vault key name for an aggregate. Lower-cased and restricted to the
     * Vault-safe charset `[a-z0-9._-]`; the two id parts are joined so distinct aggregates never map to
     * the same key. Pure / unit-testable.
     */
    internal fun keyName(key: AggregateKey): String {
        fun sanitize(s: String) = s.lowercase().map { if (it.isLetterOrDigit() || it in ".-_") it else '-' }.joinToString("")
        return "${sanitize(keyPrefix)}-${sanitize(key.aggregateType)}-${sanitize(key.aggregateId)}"
    }

    /** Performs one Vault HTTP call, returning (status, body). `open` so tests stub it without a server. */
    protected open suspend fun vaultRequest(method: String, path: String, body: String?): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val uri = URI.create("${url.trimEnd('/')}/v1/$path")
            val publisher =
                if (body == null) HttpRequest.BodyPublishers.noBody()
                else HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
            val request = HttpRequest.newBuilder(uri)
                .header("X-Vault-Token", token)
                .header("Content-Type", "application/json")
                .method(method, publisher)
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() to response.body()
        }
}
