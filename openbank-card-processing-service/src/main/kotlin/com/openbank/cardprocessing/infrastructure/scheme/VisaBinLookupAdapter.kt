// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.scheme

import com.openbank.libs.domain.cards.scheme.BinAttributes
import com.openbank.libs.domain.cards.scheme.BinLookupPort
import com.openbank.libs.domain.cards.scheme.CardScheme
import com.openbank.libs.domain.cards.scheme.FundingSource
import com.openbank.libs.domain.cards.scheme.SchemeFailure
import com.openbank.libs.domain.cards.scheme.SchemeResult
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.WebApplicationException
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.util.Optional

/**
 * Visa's BIN attributes behind [BinLookupPort] (ADR-0283 phase 2, #8810).
 *
 * ## Disabled is a state, not a failure
 *
 * With no API key configured this adapter answers [SchemeFailure.NOT_BOUND] on every call and makes
 * no request. That is a different fact from "Visa was unreachable", and a caller acts on it
 * differently: NOT_BOUND is permanent until someone configures a credential, UNAVAILABLE clears on
 * its own. Collapsing them is how a switched-off integration comes to look like a flaky one.
 *
 * ## Every answer says Visa answered it
 *
 * [CardScheme.VISA] on the result, so a stored or logged answer records which binding produced it
 * and a simulated BIN can never be mistaken for a Visa one.
 */
@ApplicationScoped
class VisaBinLookupAdapter(
    @RestClient private val client: VisaSchemeClient,
    // Optional, not a String with an empty default: application.yaml DEFINES this as `${VISA_API_KEY:}`
    // and SmallRye reads an empty value as NO value, so a non-Optional injection throws SRCFG00040
    // at startup and the service never boots — before any check in this class can run (#5844).
    @ConfigProperty(name = "openbank.card-processing.scheme.visa.api-key")
    private val apiKey: Optional<String>,
) : BinLookupPort {

    private val log = Logger.getLogger(VisaBinLookupAdapter::class.java)

    override suspend fun lookup(bin: String): SchemeResult<BinAttributes> {
        val key = apiKey.orElse("")
        if (key.isBlank()) {
            return SchemeResult.Unanswered(
                SchemeFailure.NOT_BOUND,
                CardScheme.VISA,
                "openbank.card-processing.scheme.visa.api-key is unset",
            )
        }
        return try {
            val response = client.binAttributes(bin, key)
            SchemeResult.Answered(
                BinAttributes(
                    bin = response.binNumber ?: bin,
                    // A missing brand is not "unknown brand" — it means the shape changed, which
                    // is MALFORMED. Substituting a placeholder would publish a card brand Visa
                    // never sent.
                    brand = response.cardBrand ?: return malformed("cardBrand absent"),
                    productType = response.cardType,
                    fundingSource = fundingSource(response.fundingSource),
                    issuerName = response.issuerName,
                    issuerCountry = response.issuerCountryCode,
                ),
                CardScheme.VISA,
            )
        } catch (e: WebApplicationException) {
            val status = e.response?.status ?: 0
            SchemeResult.Unanswered(httpStatusToFailure(status), CardScheme.VISA, "HTTP $status")
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Deliberately broad: a vendor lookup must never be able to fail the money path it
            // enriches. The distinct outcome is what keeps the failure visible.
            log.debugf(e, "visa BIN lookup failed for %s", bin)
            SchemeResult.Unanswered(SchemeFailure.UNAVAILABLE, CardScheme.VISA, e.message)
        }
    }

    private fun malformed(detail: String): SchemeResult<BinAttributes> =
        SchemeResult.Unanswered(SchemeFailure.MALFORMED, CardScheme.VISA, detail)

    /**
     * Unrecognised funding source maps to [FundingSource.UNKNOWN], which is honest: the enum has a
     * value for "the network did not tell us in a way we recognise", and inventing DEBIT would put
     * a guess into a stored row.
     */
    private fun fundingSource(raw: String?): FundingSource = when (raw?.uppercase()) {
        "DEBIT", "D" -> FundingSource.DEBIT
        "CREDIT", "C" -> FundingSource.CREDIT
        "PREPAID", "P" -> FundingSource.PREPAID
        "DEFERRED DEBIT", "DEFERRED_DEBIT" -> FundingSource.DEFERRED_DEBIT
        else -> FundingSource.UNKNOWN
    }
}
