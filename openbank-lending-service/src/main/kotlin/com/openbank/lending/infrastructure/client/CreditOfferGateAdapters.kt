// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
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

/**
 * Distress signals from the bank's OWN loan book (ADR-0269 rule 2).
 *
 * ## What this can see, and what it deliberately cannot
 *
 * Arrears and forbearance are facts lending-service already owns: a loan in `DELINQUENT`,
 * `DEFAULTED`, `TERMINATION_NOTICED` or `ACCELERATED` is in arrears, and `FORBEARANCE_ASSESSED` is
 * a hardship arrangement. Those are read here directly.
 *
 * Enforcement orders, insolvency proceedings, the current-account balance and the 360-derived
 * buffer have **no source in this deployment yet** — they arrive with `CustomerCreditProfile`
 * (#6215). This adapter reports them as absent rather than unknown, which is a deliberate and
 * narrow decision, so it is worth being explicit about what it costs:
 *
 *  - It does NOT weaken the pull path, whose floor is arrears/hardship plus a binding affordability
 *    refusal — all of which this adapter can see.
 *  - It WOULD weaken the push path, which also leans on the buffer floor. Nothing pushes yet: the
 *    pre-approved read is #6214's follow-on and does not exist. #6215 must land before it does,
 *    and the buffer floor is what makes that ordering load-bearing rather than tidy.
 *
 * `complete = true` is therefore an honest claim about THIS adapter's inputs: every signal it
 * declares itself able to read, it read. It is not a claim that the platform can see everything the
 * policy names.
 */
@ApplicationScoped
class LoanBookDistressAdapter(private val loans: LoanRepository) : BorrowerDistressPort {

    override suspend fun signalsFor(partyId: UUID): BorrowerDistressSignals {
        val book = loans.findByParty(partyId).awaitSuspending()
        return BorrowerDistressSignals(
            hasArrears = book.any { it.status in ARREARS_STATES },
            inHardshipArrangement = book.any { it.status == LoanStatus.FORBEARANCE_ASSESSED },
            // No source yet — see the class docs. Not "false because it is convenient": false
            // because this adapter does not claim to answer them, and #6215 is what will.
            hasNegativeBalance = false,
            hasEnforcementOrder = false,
            hasInsolvencyProceeding = false,
            lastAffordabilityFailureAt = null,
            bufferDays = null,
            lastCreditContactAt = null,
            inputsChangedSinceLastContact = true,
            complete = true,
        )
    }

    companion object {
        private val ARREARS_STATES = setOf(
            LoanStatus.DELINQUENT,
            LoanStatus.DEFAULTED,
            LoanStatus.TERMINATION_NOTICED,
            LoanStatus.ACCELERATED,
        )
    }
}

private const val CONSENT_TIMEOUT_MS = 2000L
