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
import jakarta.enterprise.inject.Instance
import jakarta.ws.rs.WebApplicationException
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.util.Optional

/**
 * Mastercard's BIN lookup behind [BinLookupPort] (ADR-0283 phase 2, #8810).
 *
 * ## Two things have to be present, and both are checked
 *
 * A consumer key AND a signing key. Mastercard authenticates by RSA signature, so a consumer key
 * with no private key produces no header at all — checking only the key would turn a missing
 * credential into a null-pointer at the first request rather than an honest NOT_BOUND.
 *
 * The signer is injected as an [Instance] because it is produced only when a key is configured;
 * `isResolvable` is the honest test for "this deployment has a Mastercard credential".
 */
@ApplicationScoped
class MastercardBinLookupAdapter(
    @RestClient private val client: MastercardSchemeClient,
    private val signers: Instance<MastercardOAuthSigner>,
    // Optional for the same reason as the Visa key: an empty yaml expansion is NO value to
    // SmallRye, and a non-Optional injection stops the service booting (#5844).
    @ConfigProperty(name = "openbank.card-processing.scheme.mastercard.base-url")
    private val baseUrl: Optional<String>,
) : BinLookupPort {

    private val log = Logger.getLogger(MastercardBinLookupAdapter::class.java)

    override suspend fun lookup(bin: String): SchemeResult<BinAttributes> {
        val url = baseUrl.orElse("")
        if (!signers.isResolvable || url.isBlank()) {
            return SchemeResult.Unanswered(
                SchemeFailure.NOT_BOUND,
                CardScheme.MASTERCARD,
                "no Mastercard consumer key, signing key or base URL is configured",
            )
        }
        val signer = signers.get()
        return try {
            // The signed parameters MUST be the ones on the wire, so the query map is built once
            // and used for both. A parameter added after signing invalidates the signature, and
            // the resulting 401 reads like a credential problem.
            val parameters = mapOf("accountRange" to bin)
            val header = signer.authorizationHeader(
                method = "GET",
                url = "$url$BIN_PATH",
                queryParameters = parameters,
            )
            val response = client.binLookup(bin, header)
            SchemeResult.Answered(
                BinAttributes(
                    bin = response.lowAccountRange ?: bin,
                    brand = response.brandProductName ?: response.brandProductCode
                        ?: return malformed("neither brandProductName nor brandProductCode present"),
                    productType = response.brandProductCode,
                    fundingSource = fundingSource(response.fundingSource),
                    issuerName = response.customerName,
                    issuerCountry = response.country,
                ),
                CardScheme.MASTERCARD,
            )
        } catch (e: WebApplicationException) {
            val status = e.response?.status ?: 0
            SchemeResult.Unanswered(httpStatusToFailure(status), CardScheme.MASTERCARD, "HTTP $status")
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Deliberately broad, same reason as the Visa adapter: a vendor lookup must never be
            // able to fail the money path it enriches.
            log.debugf(e, "mastercard BIN lookup failed for %s", bin)
            SchemeResult.Unanswered(SchemeFailure.UNAVAILABLE, CardScheme.MASTERCARD, e.message)
        }
    }

    private fun malformed(detail: String): SchemeResult<BinAttributes> =
        SchemeResult.Unanswered(SchemeFailure.MALFORMED, CardScheme.MASTERCARD, detail)

    private fun fundingSource(raw: String?): FundingSource = when (raw?.uppercase()) {
        "DEBIT" -> FundingSource.DEBIT
        "CREDIT" -> FundingSource.CREDIT
        "PREPAID" -> FundingSource.PREPAID
        "DEFERRED DEBIT", "DEFERRED_DEBIT", "CHARGE" -> FundingSource.DEFERRED_DEBIT
        else -> FundingSource.UNKNOWN
    }

    private companion object {
        /** Must equal the client's own `@Path` values; the signature covers the URL. */
        const val BIN_PATH = "/bin-resources/bin-ranges/account-searches"
    }
}
