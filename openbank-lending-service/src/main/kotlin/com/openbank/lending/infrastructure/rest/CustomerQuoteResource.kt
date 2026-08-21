// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.rest

import com.openbank.lending.application.usecase.CreditOfferEligibilityService
import com.openbank.lending.domain.model.CreditOfferDecision
import com.openbank.lending.domain.model.OfferSurface
import com.openbank.lending.infrastructure.intake.CustomerIntakeConfig
import com.openbank.libs.authz.Authorize
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.Money
import com.openbank.libs.lending.CreditQuote
import com.openbank.libs.lending.CreditQuoteCalculator
import com.openbank.libs.lending.CreditQuoteRequest
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.asUni
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Indicative pricing for the customer app (ADR-0269 rule 4).
 *
 * ## Why a quote endpoint exists at all
 *
 * Before this, the app had two choices for showing a customer what a loan would cost: compute the
 * instalment itself, or show nothing. It showed nothing — the right call, and a poor experience.
 * This route is the third option, and the ONLY one the ADR permits: the server prices, the client
 * renders.
 *
 * ## Why it is a POST that stores nothing
 *
 * A quote is a calculation over a body, not a resource. Nothing is persisted: an indicative price
 * with an id would be one lookup away from being treated as a binding offer, which it is not —
 * an offer comes from the decision engine after an assessment and carries an accept path.
 *
 * ## Why it is gated on eligibility
 *
 * A quote is a price for credit, and ADR-0269 rule 2's distress floor applies to showing prices as
 * much as to pushing offers: a customer in arrears asking "what would this cost" must not be handed
 * a tailored instalment. The gate returns a reason code, and the route surfaces it as a 409 rather
 * than a price — a refusal a client can render honestly instead of a number it must hide.
 *
 * Note the asymmetry with the pre-approved read: a customer who ASKED is exercising pull, so the
 * consent check is not what refuses them here; the distress floor is.
 */
@Path("/api/v1/lending/intake")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Lending", description = "Customer self-service origination intake")
@RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
class CustomerQuoteResource(
    private val config: CustomerIntakeConfig,
    private val eligibility: CreditOfferEligibilityService,
    private val identity: SecurityIdentity,
    private val clock: Clock,
) {
    @POST
    @Path("/quotes")
    @Authorize(action = "lending.intake", resource = "")
    @Operation(summary = "Indicative, non-binding price for a requested amount and term (edge only)")
    fun quote(
        @HeaderParam(CustomerIntakeResource.PARTY_HEADER) partyHeader: String?,
        request: CustomerQuoteRequest,
    ): Uni<Response> {
        val refusal = refuse(partyHeader, request)
        if (refusal != null) return Uni.createFrom().item(refusal)
        val partyId = UUID.fromString(partyHeader)

        return CoroutineScope(Dispatchers.Unconfined).async {
            when (val decision = eligibility.evaluate(partyId, OfferSurface.PULL)) {
                is CreditOfferDecision.Suppressed -> suppressed(decision)
                is CreditOfferDecision.Allowed -> Response.ok(priceOf(request).toDto()).build()
            }
        }.asUni()
    }

    /** Price the request from CONFIGURED terms. Nothing about the price comes from the body. */
    private fun priceOf(request: CustomerQuoteRequest): CreditQuote = CreditQuoteCalculator.quote(
        request = CreditQuoteRequest(
            principal = Money(request.amount, CurrencyCode.of(config.currency)),
            termMonths = request.termMonths,
            nominalAnnualRate = config.nominalAnnualRate.get(),
        ),
        now = Instant.now(clock),
        validityDuration = QUOTE_VALIDITY,
        firstDueDate = LocalDate.now(clock).withDayOfMonth(1).plusMonths(2),
    )

    /**
     * A suppressed quote is a 409 with the reason code and NO price. Not a 200 with nulls: a body
     * shaped like a quote, holding no numbers, is the kind of thing a client renders as "0".
     */
    private fun suppressed(decision: CreditOfferDecision.Suppressed): Response = Response.status(HTTP_CONFLICT)
        .entity(mapOf("error" to "quote unavailable", "reasonCode" to decision.code.name))
        .build()

    @Suppress("ReturnCount")
    private fun refuse(partyHeader: String?, request: CustomerQuoteRequest): Response? {
        if (!config.enabled) return error(HTTP_FORBIDDEN, "customer self-service intake is disabled")
        val permitted = config.callerPrincipal.orElse("")
        if (permitted.isBlank() || identity.principal?.name != permitted) {
            return error(HTTP_FORBIDDEN, "caller is not the customer-edge intake principal")
        }
        val partyId = partyHeader?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return error(HTTP_BAD_REQUEST, "${CustomerIntakeResource.PARTY_HEADER} is missing or not a UUID")
        if (partyId ==
            ZERO_UUID
        ) {
            return error(HTTP_BAD_REQUEST, "${CustomerIntakeResource.PARTY_HEADER} is the nil UUID")
        }
        if (config.nominalAnnualRate.isEmpty) {
            return error(HTTP_FORBIDDEN, "self-service product has no configured price")
        }
        // The same bounds as intake: a quote for an amount that could never be applied for is a
        // price the customer cannot act on, which is worse than a refusal.
        if (request.amount < config.minAmount || request.amount > config.maxAmount) {
            return error(HTTP_BAD_REQUEST, "amount outside [${config.minAmount}, ${config.maxAmount}]")
        }
        val currency = runCatching { CurrencyCode.of(config.currency) }.getOrNull()
            ?: return error(HTTP_FORBIDDEN, "self-service product currency is not a valid ISO code")
        if (request.amount.scale() > currency.defaultFractionDigits) {
            return error(HTTP_BAD_REQUEST, "amount has more than ${currency.defaultFractionDigits} decimal places")
        }
        if (request.termMonths < config.minTermMonths || request.termMonths > config.maxTermMonths) {
            return error(HTTP_BAD_REQUEST, "termMonths outside [${config.minTermMonths}, ${config.maxTermMonths}]")
        }
        return null
    }

    private fun error(status: Int, message: String): Response =
        Response.status(status).entity(mapOf("error" to message)).build()

    private fun CreditQuote.toDto() = CustomerQuoteDto(
        amount = principal.amount.toPlainString(),
        currency = principal.currency.code,
        termMonths = termMonths,
        nominalAnnualRatePercent = (nominalAnnualRate * BigDecimal(PERCENT)).toPlainString(),
        monthlyPayment = monthlyPayment.amount.toPlainString(),
        totalPayable = totalPayable.amount.toPlainString(),
        totalCostOfCredit = totalCostOfCredit.amount.toPlainString(),
        // Null stays null all the way to the client: an APRC that could not be computed must be
        // rendered as absent, never as 0%, which reads as free credit.
        aprcPercent = aprc?.let { (it * BigDecimal(PERCENT)).toPlainString() },
        validUntil = validUntil.toString(),
        binding = false,
    )

    companion object {
        /** Long enough to think about, short enough that published terms cannot drift underneath it. */
        private val QUOTE_VALIDITY: Duration = Duration.ofDays(14)
        private val ZERO_UUID = UUID(0, 0)
        private const val PERCENT = 100
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_CONFLICT = 409
    }
}

/** What the app may choose: an amount and a term. Never a rate — see [CustomerIntakeRequest]. */
data class CustomerQuoteRequest(val amount: BigDecimal, val termMonths: Int)

/**
 * The wire shape. [binding] is always false and is present precisely so the client never has to
 * infer it: a field that says "this is not a commitment" cannot be forgotten the way an unwritten
 * assumption can.
 */
data class CustomerQuoteDto(
    val amount: String,
    val currency: String,
    val termMonths: Int,
    val nominalAnnualRatePercent: String,
    val monthlyPayment: String,
    val totalPayable: String,
    val totalCostOfCredit: String,
    val aprcPercent: String?,
    val validUntil: String,
    val binding: Boolean,
)
