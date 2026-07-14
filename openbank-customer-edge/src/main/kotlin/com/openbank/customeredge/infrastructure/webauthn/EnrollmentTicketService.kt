// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.webauthn

import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Short-lived, HMAC-signed enrollment ticket (ADR-0066 F2, variant B1).
 *
 * `POST /onboarding/start` creates a party via M2M anonymously — there is no Keycloak session
 * yet. The ticket lets the brand-new device carry proof of "I am party X, freshly created" into
 * [WebAuthnResource]'s `register/begin`/`register/complete` WITHOUT it being (or needing to look
 * like) a real Keycloak-issued JWT — [WebAuthnResource] is deliberately hosted in an un-annotated
 * resource class for exactly this reason (see its KDoc): a class with any `@RolesAllowed`
 * triggers Quarkus's proactive OIDC filter, which would try to validate this ticket as a JWT and
 * reject it with a generic 401 before application code ever runs.
 *
 * Format: `base64url(partyId) + "." + expiresAtEpochSeconds + "." + hex(HMAC-SHA256)`. The MAC
 * covers `partyId + "." + expiresAtEpochSeconds` so neither field can be tampered with
 * independently.
 */
@ApplicationScoped
class EnrollmentTicketService {

    @ConfigProperty(name = "openbank.webauthn.enrollment-ticket-secret", defaultValue = "")
    lateinit var secret: String

    fun issue(partyId: String): String {
        val expiresAt = (System.currentTimeMillis() / MILLIS_PER_SECOND) + TTL_SECONDS
        val payload = "$partyId.$expiresAt"
        val mac = hmacHex(payload)
        val partyIdB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(partyId.toByteArray(Charsets.UTF_8))
        return "$partyIdB64.$expiresAt.$mac"
    }

    /** Returns the bound partyId if [ticket] is well-formed, unexpired and its MAC verifies — null otherwise. */
    fun verify(ticket: String): String? {
        if (secret.isBlank()) {
            Log.warn("enrollment-ticket-secret not configured — rejecting all enrollment tickets")
            return null
        }
        val parts = ticket.split(".")
        if (parts.size != TICKET_PARTS_COUNT) return null
        val (partyIdB64, expiresAtRaw, mac) = parts
        val expiresAt = expiresAtRaw.toLongOrNull() ?: return null
        if (System.currentTimeMillis() / MILLIS_PER_SECOND >= expiresAt) return null
        val partyId = runCatching {
            String(Base64.getUrlDecoder().decode(partyIdB64), Charsets.UTF_8)
        }.getOrNull() ?: return null
        val expected = hmacHex("$partyId.$expiresAt")
        // Constant-time compare — this is a bearer-credential MAC, not a display value.
        return if (constantTimeEquals(expected, mac)) partyId else null
    }

    private fun hmacHex(payload: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    companion object {
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val MILLIS_PER_SECOND = 1000L
        private const val TICKET_PARTS_COUNT = 3

        // The device round-trip (register/begin -> Face ID -> register/complete) is seconds;
        // 10 minutes covers a slow network / app backgrounding mid-flow without leaving a
        // long-lived bearer-equivalent credential lying around.
        const val TTL_SECONDS = 10L * 60
    }
}
