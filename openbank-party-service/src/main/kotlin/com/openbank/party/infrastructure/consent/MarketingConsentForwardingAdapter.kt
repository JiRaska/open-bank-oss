// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.consent

import com.openbank.party.application.port.out.MarketingConsentForwardingException
import com.openbank.party.application.port.out.MarketingConsentForwardingPort
import com.openbank.party.infrastructure.client.ConsentServiceRestClient
import com.openbank.party.infrastructure.client.CreateMarketingConsentRequest
import com.openbank.party.infrastructure.client.RevokeMarketingConsentRequest
import com.openbank.party.infrastructure.kafka.MarketingConsentEventConsumer.Companion.MARKETING_GRANTEE_ID
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.WebApplicationException
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Forwards the mobile app's marketing-consent toggle to consent-service (ADR-0198 D3, ADR-0205,
 * ADR-0206 D5) instead of party-service writing `consent_marketing` directly. Unlike
 * [com.openbank.party.infrastructure.gdpr.GdprAggregationAdapter] (a best-effort read), a failure
 * here always propagates — see [MarketingConsentForwardingPort]'s KDoc.
 */
@ApplicationScoped
class MarketingConsentForwardingAdapter(
    @RestClient private val consentClient: ConsentServiceRestClient,
    private val clock: Clock,
) : MarketingConsentForwardingPort {

    override suspend fun grant(partyId: UUID): UUID {
        val request = CreateMarketingConsentRequest(
            partyId = partyId,
            granteeId = MARKETING_GRANTEE_ID,
            granteeType = "INTERNAL_SERVICE",
            granteeName = "Party marketing preferences",
            scopes = MARKETING_SCOPES,
            accountIbans = null,
            // consent-service clamps non-AISP scopes to 365 days server-side regardless of what's
            // requested (ConsentService.createConsent) — sending the max here just states intent.
            validTo = OffsetDateTime.now(clock).plusDays(MAX_VALIDITY_DAYS),
            redirectUri = null,
            tppTransactionId = null,
        )
        return runCatching { consentClient.create(request).awaitSuspending() }
            .getOrElse { t -> throw forwardingFailure("grant", partyId, t) }
            .id
    }

    override suspend fun revoke(partyId: UUID, consentId: UUID, reason: String) {
        runCatching {
            consentClient.revoke(consentId, partyId, MARKETING_GRANTEE_ID, RevokeMarketingConsentRequest(reason))
                .awaitSuspending()
        }.getOrElse { t -> throw forwardingFailure("revoke", partyId, t) }
    }

    private fun forwardingFailure(op: String, partyId: UUID, t: Throwable): MarketingConsentForwardingException {
        val status = (t as? WebApplicationException)?.response?.status
        return MarketingConsentForwardingException(
            "consent-service $op failed for party $partyId" + (status?.let { " (HTTP $it)" } ?: ""),
            t,
        )
    }

    companion object {
        private const val MAX_VALIDITY_DAYS = 365L
        private val MARKETING_SCOPES = setOf("MARKETING_COMMS_EMAIL", "MARKETING_COMMS_PUSH", "MARKETING_COMMS_INAPP")
    }
}
