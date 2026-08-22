// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.openbank.lending.application.port.out.BorrowerDistressPort
import com.openbank.lending.application.port.out.CreditOffersConsentPort
import com.openbank.lending.application.port.out.LoanRepository
import com.openbank.lending.domain.model.BorrowerDistressSignals
import com.openbank.lending.domain.model.LoanStatus
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

/** consent-service's own active-consent check, reused verbatim (the shape campaign-service uses). */
@RegisterRestClient(configKey = "consent-service")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/consents")
@Produces(MediaType.APPLICATION_JSON)
interface LendingConsentServiceClient {
    @GET
    @Path("/party/{partyId}/grantee/{granteeId}/active")
    @Timeout(CONSENT_TIMEOUT_MS)
    fun hasActiveConsent(
        @PathParam("partyId") partyId: UUID,
        @PathParam("granteeId") granteeId: String,
        @QueryParam("scope") scope: String,
    ): io.smallrye.mutiny.Uni<ConsentCheckResult>
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ConsentCheckResult(val granted: Boolean = false)

/**
 * Reads `CREDIT_OFFERS` from consent-service (ADR-0269 rule 1).
 *
 * Returns null on any failure rather than false: the service already treats null as a refusal, and
 * keeping the two apart is what lets an outage be alerted on instead of hiding inside a normal-
 * looking suppression count.
 */
@ApplicationScoped
class RestCreditOffersConsentAdapter(@param:RestClient private val consents: LendingConsentServiceClient) :
    CreditOffersConsentPort {

    override suspend fun hasCreditOffersConsent(partyId: UUID): Boolean? =
        runCatching { consents.hasActiveConsent(partyId, BANK_GRANTEE, CREDIT_OFFERS_SCOPE).awaitSuspending() }
            .getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            }?.granted

    companion object {
        /** The bank itself is the grantee for a first-party marketing consent, not a TPP. */
        const val BANK_GRANTEE = "openbank"
        const val CREDIT_OFFERS_SCOPE = "CREDIT_OFFERS"
    }
}

/** analytics-sink's ADR-0269 credit profile (#6215) — the single definition of these numbers. */
@RegisterRestClient(configKey = "analytics-sink")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/analytics/credit-profile")
@Produces(MediaType.APPLICATION_JSON)
interface CreditProfileClient {
    @GET
    @Path("/{partyId}")
    @Timeout(PROFILE_TIMEOUT_MS)
    fun profile(@PathParam("partyId") partyId: UUID): io.smallrye.mutiny.Uni<CreditProfileResponse>
}

/**
 * [monthsObserved] is part of the answer, not metadata: a three-week-old customer and a five-year
 * customer can produce the same median, and the gate refuses to treat the first as evidence.
 * Amounts are strings on the wire for the reason they always are here — money never crosses an API
 * as a Double.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class CreditProfileResponse(
    // The wire names are ClickHouse column names (snake_case) and the Kotlin names are Kotlin
    // names; @JsonProperty is what keeps that translation in one visible place instead of forcing
    // one convention onto the other.
    @param:JsonProperty("months_observed") val monthsObserved: Int = 0,
    @param:JsonProperty("income_monthly") val incomeMonthly: String? = null,
    @param:JsonProperty("outflow_monthly") val outflowMonthly: String? = null,
    @param:JsonProperty("net_monthly") val netMonthly: String? = null,
    @param:JsonProperty("volatility_ratio") val volatilityRatio: String? = null,
)

/**
 * Distress signals from the bank's OWN loan book, plus the 360 profile (ADR-0269 rule 2).
 *
 * ## What this can see, and what it deliberately cannot
 *
 * Arrears and forbearance are facts lending-service already owns: a loan in `DELINQUENT`,
 * `DEFAULTED`, `TERMINATION_NOTICED` or `ACCELERATED` is in arrears, and `FORBEARANCE_ASSESSED` is
 * a hardship arrangement. Those are read here directly.
 *
 * Cash-flow cover and history depth now come from analytics-sink's `gold_party_credit_profile`
 * (#6215). [BorrowerDistressSignals.bufferDays] is derived from the profile as net monthly cover:
 * how many days the median monthly surplus would carry the median monthly outflow. A party with no
 * surplus has zero days of cover, which is the honest reading and the one the buffer floor exists
 * to catch.
 *
 * Enforcement orders and insolvency proceedings still have **no source in this deployment** — no
 * service ingests a court register. They are reported as absent rather than unknown, deliberately:
 * treating them as unknown would make `complete = false` permanent and suppress every offer
 * forever, which is indistinguishable from the feature being switched off and would hide the
 * moment a real signal source arrives. The gap is real and is what an operational-risk review of
 * this gate should challenge first.
 *
 * A profile that cannot be read is NOT substituted with defaults — `complete = false` propagates
 * and the gate refuses. That is the difference between "the customer has no surplus" and "we could
 * not find out", which no downstream layer can reconstruct afterwards.
 */
@ApplicationScoped
class LoanBookDistressAdapter(
    private val loans: LoanRepository,
    @param:RestClient private val profiles: CreditProfileClient,
) : BorrowerDistressPort {

    override suspend fun signalsFor(partyId: UUID): BorrowerDistressSignals {
        val book = loans.findByParty(partyId).awaitSuspending()
        val profile = runCatching { profiles.profile(partyId).awaitSuspending() }
            .getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            }
        return BorrowerDistressSignals(
            hasArrears = book.any { it.status in ARREARS_STATES },
            inHardshipArrangement = book.any { it.status == LoanStatus.FORBEARANCE_ASSESSED },
            // A negative median net over six months is the closest thing to "living on the
            // overdraft" that the profile can see. It is not a live balance read, and it is not
            // claimed to be one.
            hasNegativeBalance = profile?.netMonthlyOrNull()?.signum()?.let { it < 0 } ?: false,
            // No source in this deployment — see the class docs.
            hasEnforcementOrder = false,
            hasInsolvencyProceeding = false,
            lastAffordabilityFailureAt = null,
            bufferDays = profile?.let { coverDays(it) },
            monthsObserved = profile?.monthsObserved,
            lastCreditContactAt = null,
            inputsChangedSinceLastContact = true,
            // False when the profile could not be read: the gate must refuse rather than price a
            // customer whose cash flow the bank just failed to look up.
            complete = profile != null,
        )
    }

    /**
     * Days of cover the monthly surplus buys against the monthly outflow.
     *
     * `net / outflow × 30`, floored at zero. Null outflow means nothing goes out, which is not
     * infinite cover but an unobserved customer — null, so the buffer floor suppresses.
     */
    private fun coverDays(profile: CreditProfileResponse): Int? {
        val net = profile.netMonthlyOrNull() ?: return null
        val outflow = profile.outflowMonthlyOrNull() ?: return null
        if (outflow.signum() <= 0) return null
        if (net.signum() <= 0) return 0
        return net.multiply(java.math.BigDecimal(DAYS_PER_MONTH))
            .divide(outflow, java.math.MathContext.DECIMAL64)
            .toInt()
    }

    companion object {
        private const val DAYS_PER_MONTH = 30
        private val ARREARS_STATES = setOf(
            LoanStatus.DELINQUENT,
            LoanStatus.DEFAULTED,
            LoanStatus.TERMINATION_NOTICED,
            LoanStatus.ACCELERATED,
        )
    }
}

private fun CreditProfileResponse.netMonthlyOrNull(): java.math.BigDecimal? =
    netMonthly?.takeIf { it.isNotBlank() }?.let { runCatching { java.math.BigDecimal(it) }.getOrNull() }

private fun CreditProfileResponse.outflowMonthlyOrNull(): java.math.BigDecimal? =
    outflowMonthly?.takeIf { it.isNotBlank() }?.let { runCatching { java.math.BigDecimal(it) }.getOrNull() }

private const val CONSENT_TIMEOUT_MS = 2000L
private const val PROFILE_TIMEOUT_MS = 2000L
