// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import com.openbank.libs.governance.StaffIdentity
import org.eclipse.microprofile.jwt.JsonWebToken

/**
 * Reads a [StaffIdentity] from the VERIFIED access token (the injected [JsonWebToken], whose
 * signature, issuer and expiry Quarkus OIDC has already checked). Never from a header or body.
 *
 * Claims (Keycloak realm `openbank`, see `realm-template.json`):
 *  - `person_id` — user attribute of the same name, mapped by the `basic` client scope. Every staff
 *    account of one natural person carries the same value. Absent → `personId = null`, and every
 *    person-bound four-eyes step fails closed.
 *  - `amr` — Authentication Method Reference (RFC 8176), emitted by the `amr` mapper from the
 *    authenticator reference values (`pwd`, `otp`). A second factor is present when `amr` contains
 *    one of [SECOND_FACTOR_AMR]. `acr` is deliberately NOT used: the realm's browser flow has no
 *    level-of-authentication conditions, so `acr` reads `pwd` even after an OTP.
 */
object StaffIdentities {
    const val PERSON_ID_CLAIM: String = "person_id"
    const val AMR_CLAIM: String = "amr"

    /** RFC 8176 values that prove a factor beyond the password. */
    val SECOND_FACTOR_AMR: Set<String> = setOf("otp", "mfa", "hwk", "swk", "sc", "fido", "webauthn")

    /** The acting staff identity; a `null` token yields an identity with no person (fails closed). */
    fun from(jwt: JsonWebToken?): StaffIdentity {
        if (jwt == null) return StaffIdentity(account = "anonymous", personId = null, strongAuth = false)
        val account = jwt.name ?: jwt.subject ?: "anonymous"
        val personId = claimStrings(jwt.getClaim<Any?>(PERSON_ID_CLAIM)).singleOrNull()
        val amr = claimStrings(jwt.getClaim<Any?>(AMR_CLAIM))
        return StaffIdentity(account = account, personId = personId, strongAuth = amr.any { it in SECOND_FACTOR_AMR })
    }

    /**
     * Normalises a claim to strings. SmallRye JWT hands back `jakarta.json` values (a `JsonString`
     * prints WITH quotes), plain Strings, or collections of either; a multi-valued `person_id` is
     * treated as absent by [from] (`singleOrNull`) — an ambiguous person is no person.
     */
    internal fun claimStrings(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is String -> listOf(value)
        is Collection<*> -> value.flatMap { claimStrings(it) }
        is Array<*> -> value.flatMap { claimStrings(it) }
        else -> listOf(value.toString().trim().removeSurrounding("\""))
    }.map { it.trim() }.filter { it.isNotEmpty() }
}
