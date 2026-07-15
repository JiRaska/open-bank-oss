// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.webauthn

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.logging.Log
import io.quarkus.redis.datasource.RedisDataSource
import jakarta.enterprise.context.ApplicationScoped

/**
 * A registered WebAuthn credential (ADR-0066 F2). [attestedCredentialDataB64] is the webauthn4j
 * `AttestedCredentialData` (AAGUID + credential id + COSE public key), CBOR-serialised via
 * [com.webauthn4j.converter.AttestedCredentialDataConverter] and base64-encoded — everything
 * needed to reconstruct an `Authenticator` for a later assertion verification. The original
 * attestation statement is deliberately NOT persisted: trust in it is a registration-time-only
 * decision (RP policy on what authenticator models to accept), never re-checked at login, so
 * [WebAuthnResource] reconstructs the `Authenticator` for auth with a `NoneAttestationStatement`
 * placeholder rather than storing the (potentially large, device-identifying) real one.
 */
data class RegisteredCredential(
    val credentialId: String, // base64url — also the Redis key suffix
    val partyId: String,
    val keycloakUserId: String,
    val attestedCredentialDataB64: String,
    val signCount: Long,
)

/**
 * Redis-backed WebAuthn credential store (ADR-0066 F2), keyed by credential id. Mirrors
 * [com.openbank.customeredge.infrastructure.onboarding.PendingOnboardingStore]'s pattern: the
 * blocking [RedisDataSource] (no coroutines) so it can be called directly from the `@Blocking`
 * REST methods in [WebAuthnResource].
 *
 * No TTL — a passkey is a durable credential, not an ephemeral session artifact.
 */
@ApplicationScoped
class WebAuthnStore(redis: RedisDataSource, private val objectMapper: ObjectMapper) {
    private val values = redis.value(String::class.java)

    fun save(credential: RegisteredCredential) {
        values.set(key(credential.credentialId), objectMapper.writeValueAsString(credential))
    }

    fun find(credentialId: String): RegisteredCredential? {
        val json = values.get(key(credentialId)) ?: return null
        return runCatching { objectMapper.readValue(json, RegisteredCredential::class.java) }.getOrElse { e ->
            Log.warnf(e, "WebAuthnStore: failed to deserialise credential=%s", credentialId)
            null
        }
    }

    private fun key(credentialId: String) = "edge:webauthn:cred:$credentialId"
}
