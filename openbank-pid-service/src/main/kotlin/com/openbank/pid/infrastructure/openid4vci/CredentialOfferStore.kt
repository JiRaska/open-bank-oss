// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.pid.infrastructure.openid4vci

import java.time.Instant

/**
 * In-flight OpenID4VCI pre-authorized-code credential offers (ADR-0094).
 *
 * Lifecycle: a bank back-end creates an offer (OFFERED) with the verified claims; the wallet redeems
 * the pre-authorized code at the token endpoint (→ AUTHORIZED, an access token + c_nonce are bound);
 * the wallet then calls the credential endpoint with a proof-of-possession over the c_nonce and the
 * credential is minted exactly once (→ ISSUED). The pre-auth code and access token are single-use
 * transitions, so a replayed token redemption or credential request loses the race.
 *
 * Backed by [PostgresCredentialOfferStore] in production (durable + multi-replica safe: the token and
 * credential endpoints may land on different replicas) and [InMemoryCredentialOfferStore] for tests,
 * selected by `openbank.pid.eudi.persistence`. Holds verified identity claims briefly; no secret material.
 */
interface CredentialOfferStore {
    enum class Status { OFFERED, AUTHORIZED, ISSUED, EXPIRED }

    class Offer(
        val preAuthCode: String,
        val claims: OfferedClaims,
        val createdAt: Instant,
        val expiresAt: Instant,
        @Volatile var status: Status,
        @Volatile var cNonce: String? = null,
        @Volatile var accessToken: String? = null,
    )

    suspend fun create(preAuthCode: String, claims: OfferedClaims, now: Instant): Offer

    /** Redeem the pre-authorized code exactly once: OFFERED → AUTHORIZED, binding accessToken + cNonce. */
    suspend fun authorize(preAuthCode: String, accessToken: String, cNonce: String, now: Instant): Offer?

    suspend fun findByAccessToken(accessToken: String, now: Instant): Offer?

    /** Mark the credential minted exactly once: AUTHORIZED → ISSUED. False on replay/expired. */
    suspend fun markIssued(accessToken: String, now: Instant): Boolean
}
