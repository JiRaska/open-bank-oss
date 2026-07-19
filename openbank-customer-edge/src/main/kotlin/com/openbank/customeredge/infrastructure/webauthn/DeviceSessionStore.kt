// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.webauthn

import io.quarkus.redis.datasource.RedisDataSource
import io.quarkus.redis.datasource.value.SetArgs
import jakarta.enterprise.context.ApplicationScoped
import java.security.SecureRandom
import java.util.Base64

/**
 * Redis-backed device-session store (ADR-0066 F2 refresh fix).
 *
 * The native-passkey session token minted by [WebAuthnKeycloakClient.impersonate] (RFC 8693
 * token-exchange) carries `azp=openbank-edge-webauthn`, so it is NOT refreshable by the public
 * `openbank-app` client via a normal `refresh_token` grant ("Token client and authorized client
 * don't match") — the app could therefore never silently resume a session and re-ran a full passkey
 * ceremony on every cold start.
 *
 * Instead the edge issues an OPAQUE, single-use, rotating device-session id bound to the Keycloak
 * user. On resume the app presents it and the edge re-mints a fresh access token via `impersonate()`
 * (which always works) and rotates the id. Security: the id is 256 bits of [SecureRandom], lives
 * only in the app's biometric-gated Keychain, is revocable (delete the key on logout) and TTL-bounded
 * ([TTL_SECONDS] idle, extended on each refresh) — matching the app-side 14-day re-auth window.
 */
@ApplicationScoped
class DeviceSessionStore(redis: RedisDataSource) {
    private val values = redis.value(String::class.java)
    private val rng = SecureRandom()

    /** Mint a new device-session id bound to [keycloakUserId] (idle TTL [TTL_SECONDS]). */
    fun issue(keycloakUserId: String): String {
        val id = newId()
        values.set(key(id), keycloakUserId, SetArgs().ex(TTL_SECONDS))
        return id
    }

    /**
     * Single-use resolve+rotate: returns the Keycloak user for [deviceSessionId] and atomically
     * consumes it (getdel), or null if unknown/expired/revoked. The caller issues a fresh id via
     * [issue] for the same user, so a stolen id is usable at most once before it rotates.
     */
    fun consume(deviceSessionId: String): String? = values.getdel(key(deviceSessionId))

    /** Revoke a device session (logout). */
    fun revoke(deviceSessionId: String) {
        values.getdel(key(deviceSessionId))
    }

    private fun newId(): String {
        val b = ByteArray(ID_BYTES)
        rng.nextBytes(b)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    private fun key(id: String) = "edge:devsession:$id"

    companion object {
        private const val TTL_SECONDS = 14L * 24 * 60 * 60 // 14-day idle window (matches the app)
        private const val ID_BYTES = 32 // 256-bit opaque token
    }
}
