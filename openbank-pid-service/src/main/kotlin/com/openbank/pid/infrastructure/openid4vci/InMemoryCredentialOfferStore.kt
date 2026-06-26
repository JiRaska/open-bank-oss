// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.pid.infrastructure.openid4vci

import com.openbank.pid.infrastructure.openid4vci.CredentialOfferStore.Offer
import com.openbank.pid.infrastructure.openid4vci.CredentialOfferStore.Status
import io.quarkus.arc.DefaultBean
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [CredentialOfferStore] — the original ConcurrentHashMap behaviour. Ephemeral and
 * single-replica; the test/dev fallback. Production uses [PostgresCredentialOfferStore]. Selected by
 * `openbank.pid.eudi.persistence` (default postgres). Unit tests construct it directly.
 */
@ApplicationScoped
@DefaultBean
class InMemoryCredentialOfferStore(
    @ConfigProperty(name = "openbank.pid.eudi.issuer.offer-ttl-seconds", defaultValue = "600")
    private val ttlSeconds: Long,
) : CredentialOfferStore {

    private val byCode = ConcurrentHashMap<String, Offer>()
    private val byAccessToken = ConcurrentHashMap<String, Offer>()

    override suspend fun create(preAuthCode: String, claims: OfferedClaims, now: Instant): Offer {
        evictExpired(now)
        val offer = Offer(preAuthCode, claims, now, now.plusSeconds(ttlSeconds), Status.OFFERED)
        byCode[preAuthCode] = offer
        return offer
    }

    /**
     * Drop offers past their TTL from both indexes so the in-memory store stays bounded (an attacker
     * cannot grow the heap with unredeemed offers). Called opportunistically on each create.
     */
    private fun evictExpired(now: Instant) {
        byCode.values.removeIf { it.expiresAt.isBefore(now) }
        byAccessToken.values.removeIf { it.expiresAt.isBefore(now) }
    }

    override suspend fun authorize(preAuthCode: String, accessToken: String, cNonce: String, now: Instant): Offer? {
        var bound: Offer? = null
        byCode.computeIfPresent(preAuthCode) { _, offer ->
            if (offer.status == Status.OFFERED && !now.isAfter(offer.expiresAt)) {
                offer.status = Status.AUTHORIZED
                offer.accessToken = accessToken
                offer.cNonce = cNonce
                byAccessToken[accessToken] = offer
                bound = offer
            }
            offer
        }
        return bound
    }

    override suspend fun findByAccessToken(accessToken: String, now: Instant): Offer? {
        val offer = byAccessToken[accessToken] ?: return null
        if (offer.status != Status.ISSUED && now.isAfter(offer.expiresAt)) offer.status = Status.EXPIRED
        return offer
    }

    override suspend fun markIssued(accessToken: String, now: Instant): Boolean {
        var ok = false
        byAccessToken.computeIfPresent(accessToken) { _, offer ->
            if (offer.status == Status.AUTHORIZED && !now.isAfter(offer.expiresAt)) {
                offer.status = Status.ISSUED
                ok = true
            }
            offer
        }
        return ok
    }
}
