// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.client

import com.openbank.cardprocessing.application.port.out.CardIssuancePolicyPort
import com.openbank.cardprocessing.application.port.out.CardLookupPort
import com.openbank.cardprocessing.application.port.out.CardOwnership
import com.openbank.cardprocessing.application.port.out.IssuerDecision
import com.openbank.cardprocessing.domain.model.CountedSpend
import com.openbank.cardprocessing.domain.model.PresentmentChannel
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.WebApplicationException
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Binds both card-issuance ports to the REST client.
 *
 * **Fails CLOSED.** If card-issuance cannot be reached the authorisation is declined, not approved:
 * an issuer that cannot evaluate its own controls must not let spend through, or the customer's
 * "payments abroad off" would be honoured only while the network is healthy. That is the opposite
 * of the VoP adapter's deliberate fail-open (ADR-0171), because the two answer different questions
 * — VoP warns, this one authorises.
 *
 * The unreachable case is [ISSUER_UNAVAILABLE], its own decline reason, never one borrowed from the
 * policy: "the issuer was down" and "you turned gambling off" must not look alike in a dispute, in
 * a metric or in the customer's app.
 */
@ApplicationScoped
class CardIssuanceAdapter(
    @RestClient private val client: CardIssuanceClient,
    @ConfigProperty(name = "openbank.card-processing.default-currency", defaultValue = "CZK")
    private val defaultCurrency: String,
) : CardLookupPort,
    CardIssuancePolicyPort {

    private val log = Logger.getLogger(CardIssuanceAdapter::class.java)

    override suspend fun lookup(cardId: UUID): CardOwnership? = try {
        val card = client.getCard(cardId)
        CardOwnership(
            accountId = card.accountId,
            partyId = card.partyId,
            // card-issuance does not publish a per-card currency today; the account's currency is
            // the real answer and arrives with ADR-0283 phase 2. Until then the configured issuing
            // currency is used, and it is stated here rather than hidden in a literal.
            currencyCode = card.currencyCode ?: defaultCurrency,
        )
    } catch (e: WebApplicationException) {
        if (e.response?.status == NOT_FOUND) {
            null
        } else {
            log.warnf(e, "card lookup failed for %s", cardId)
            throw e
        }
    }

    override suspend fun decide(
        cardId: UUID,
        amountMinorUnits: Long,
        channel: PresentmentChannel,
        mcc: String?,
        countryCode: String?,
        counted: CountedSpend,
    ): IssuerDecision = try {
        val response = client.authorize(
            cardId,
            IssuerAuthorizationRequest(
                amountMinorUnits = amountMinorUnits,
                // The single place the two channel vocabularies meet. The names match today; this
                // mapping is where a divergence would be caught rather than serialised blindly.
                channel = channel.name,
                mcc = mcc,
                countryCode = countryCode,
                spentTodayMinorUnits = counted.todayMinorUnits,
                spentThisMonthMinorUnits = counted.thisMonthMinorUnits,
                spentThisMonthInCategoryMinorUnits = counted.thisMonthInCategoryMinorUnits,
            ),
        )
        IssuerDecision(response.approved, response.declineReason, response.category)
    } catch (e: WebApplicationException) {
        log.errorf(e, "card-issuance did not answer for card %s — declining", cardId)
        IssuerDecision(approved = false, reason = ISSUER_UNAVAILABLE, category = UNMAPPED)
    }

    private companion object {
        const val NOT_FOUND = 404
        const val ISSUER_UNAVAILABLE = "ISSUER_UNAVAILABLE"
        const val UNMAPPED = "UNMAPPED"
    }
}
