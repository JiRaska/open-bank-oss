// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.rest

import com.openbank.lending.application.port.`in`.ApplyForLoanUseCase
import com.openbank.lending.domain.model.LoanApplicationRequest
import com.openbank.lending.infrastructure.intake.CustomerIntakeConfig
import com.openbank.libs.authz.Authorize
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.Money
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Customer self-service origination intake (ADR-0211's "Customer intake" row: customer edge,
 * ADR-0065). This is the missing half of the origination path — `LendingResource.applyForLoan` is a
 * DESK endpoint (`ROLE_LENDING_OFFICER`/`ROLE_CREDIT_RISK`/`ROLE_COMPLIANCE`/`ROLE_ADMIN`), so a
 * customer app had no route by which to apply at all.
 *
 * ## Why the caller check is in Kotlin and not only in rego
 *
 * customer-edge's M2M identity carries `ROLE_OPERATOR` and NOTHING else, and `AuthorizeInterceptor`
 * classifies a client_credentials JWT as `HUMAN` — so lending's `operator-lending-write` rego rule
 * already admits any `lending.*` action for it. Gating this endpoint on `ROLE_OPERATOR` alone would
 * therefore let *any* operator — a real person at a desk — submit an application in a customer's
 * name, with the party id supplied in a header. The named-principal check below is the actual
 * control; the rego rule added alongside is defence in depth, and neither is load-bearing alone.
 *
 * ## What is NOT trusted from the request
 *
 * The party id comes from `X-Customer-Party-Id` (set by the edge from the customer JWT, never by the
 * app), and the price, jurisdiction and product type come from configuration. A customer-supplied
 * jurisdiction would let the applicant choose which ADR-0212 compliance pack judges them; a
 * customer-supplied rate would let them price their own loan.
 *
 * This endpoint is the MAKER leg only. The application lands in the ordinary origination graph and
 * still needs a human checker (`lending.approve`, four-eyes) before anything is disbursed — a
 * customer cannot originate money by calling it.
 */
@Path("/api/v1/lending/intake")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Lending", description = "Customer self-service origination intake")
@RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
class CustomerIntakeResource(
    private val apply: ApplyForLoanUseCase,
    private val config: CustomerIntakeConfig,
    private val identity: SecurityIdentity,
    private val clock: Clock,
) {
    @POST
    @Path("/applications")
    @Authorize(action = "lending.intake", resource = "")
    @Operation(summary = "Submit a loan application on behalf of an authenticated customer (edge only)")
    fun submit(@HeaderParam(PARTY_HEADER) partyHeader: String?, request: CustomerIntakeRequest): Uni<Response> {
        val refusal = refuse(partyHeader, request)
        if (refusal != null) return Uni.createFrom().item(refusal)

        val partyId = UUID.fromString(partyHeader)
        val application = LoanApplicationRequest(
            partyId = partyId,
            requestedAmount = Money(request.amount, CurrencyCode.of(config.currency)),
            nominalAnnualRate = config.nominalAnnualRate.get(),
            termPeriods = request.termMonths,
            firstDueDate = firstDueDate(),
            jurisdiction = config.jurisdiction,
            productType = config.productType,
        )
        return apply.apply(application, "$CUSTOMER_ACTOR_PREFIX$partyId")
            .map { Response.status(HTTP_CREATED).entity(it).build() }
            .onFailure().recoverWithItem { e -> error(HTTP_UNPROCESSABLE, e.message ?: "intake refused") }
    }

    /**
     * Every guard, in one place and fail-closed: a `null` return is the ONLY way through. Written as
     * a refusal function rather than a chain of early returns inside [submit] so that adding a
     * transport concern later cannot accidentally land above a check.
     */
    @Suppress("ReturnCount")
    private fun refuse(partyHeader: String?, request: CustomerIntakeRequest): Response? {
        if (!config.enabled) return error(HTTP_FORBIDDEN, "customer self-service intake is disabled")

        val permitted = config.callerPrincipal.orElse("")
        // Blank config refuses everything. The alternative — "unset means allow any operator" — is
        // exactly the over-grant this endpoint exists to avoid, and it would arrive by omission.
        if (permitted.isBlank() || identity.principal?.name != permitted) {
            return error(HTTP_FORBIDDEN, "caller is not the customer-edge intake principal")
        }
        val partyId = partyHeader?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return error(HTTP_BAD_REQUEST, "$PARTY_HEADER is missing or not a UUID")
        if (partyId == ZERO_UUID) return error(HTTP_BAD_REQUEST, "$PARTY_HEADER is the nil UUID")

        if (config.nominalAnnualRate.isEmpty) {
            return error(HTTP_FORBIDDEN, "self-service product has no configured price")
        }
        if (request.amount < config.minAmount || request.amount > config.maxAmount) {
            return error(HTTP_BAD_REQUEST, "amount outside [${config.minAmount}, ${config.maxAmount}]")
        }
        // Money's init rejects an over-scaled amount by THROWING, which a client sees as a 500. An
        // app sending 50000.123 CZK is making an ordinary mistake and deserves an ordinary 400.
        val currency = runCatching { CurrencyCode.of(config.currency) }.getOrNull()
            ?: return error(HTTP_FORBIDDEN, "self-service product currency is not a valid ISO code")
        if (request.amount.scale() > currency.defaultFractionDigits) {
            return error(
                HTTP_BAD_REQUEST,
                "amount has more than ${currency.defaultFractionDigits} decimal places for ${currency.code}",
            )
        }
        if (request.termMonths < config.minTermMonths || request.termMonths > config.maxTermMonths) {
            return error(
                HTTP_BAD_REQUEST,
                "termMonths outside [${config.minTermMonths}, ${config.maxTermMonths}]",
            )
        }
        return null
    }

    /** First day of the month after next — a full month of runway before instalment one. */
    private fun firstDueDate(): LocalDate = LocalDate.now(clock).withDayOfMonth(1).plusMonths(2)

    private fun error(status: Int, message: String): Response =
        Response.status(status).entity(mapOf("error" to message)).build()

    companion object {
        const val PARTY_HEADER = "X-Customer-Party-Id"

        /** Actor recorded on the application. Namespaced so a customer maker can never be mistaken
         *  for a desk principal in the ADR-0214 evidence trail, nor satisfy a checker leg. */
        const val CUSTOMER_ACTOR_PREFIX = "customer:"

        private val ZERO_UUID = UUID(0, 0)
        private const val HTTP_CREATED = 201
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_UNPROCESSABLE = 422
    }
}

/**
 * What the customer app may say. Note what is ABSENT: party id, rate, jurisdiction, product type —
 * see the class KDoc. Nothing else is accepted: a field the service takes and then silently drops
 * reads to the client as an honoured request, which is worse than a 400.
 */
data class CustomerIntakeRequest(val amount: BigDecimal, val termMonths: Int)
