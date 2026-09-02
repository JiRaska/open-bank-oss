// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.rest

import com.openbank.lending.application.port.out.InstallmentRepository
import com.openbank.lending.application.port.out.LoanRepository
import com.openbank.lending.domain.model.Loan
import com.openbank.lending.domain.model.LoanStatus
import com.openbank.lending.infrastructure.client.CreditProfileClient
import com.openbank.lending.infrastructure.intake.CustomerIntakeConfig
import com.openbank.libs.authz.Authorize
import com.openbank.libs.lending.FinancialHealth
import com.openbank.libs.lending.FinancialHealthInputs
import com.openbank.libs.lending.HealthPillar
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.asUni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.math.BigDecimal
import java.util.UUID

/**
 * The customer's own financial-health view (ADR-0269 / APP-ADR-0001 rule 5).
 *
 * ## Why it is assembled HERE and not at the edge
 *
 * The four pillars need three sources — the ADR-0269 credit profile, the loan book, and the
 * customer's balances. lending-service already talks to all three. Assembling it at the edge would
 * mean a new HTTP dependency from the edge to analytics-sink (today they only meet through Kafka)
 * and product thresholds living in a proxy. The judgement itself is a pure function in libs; this
 * class only fetches.
 *
 * ## What it is not
 *
 * No score, no rating, no eligibility, and no path into a credit decision. Every pillar can answer
 * UNKNOWN, and one unavailable upstream greys out one pillar rather than the screen — a customer
 * who opened this to look at their reserve should not be told nothing is known because the profile
 * service was slow.
 */
@Path("/api/v1/lending/intake")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Lending", description = "Customer self-service origination intake")
@RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
class CustomerFinancialHealthResource(
    private val loans: LoanRepository,
    private val installments: InstallmentRepository,
    @param:RestClient private val profiles: CreditProfileClient,
    private val config: CustomerIntakeConfig,
    private val identity: SecurityIdentity,
) {
    @GET
    @Path("/financial-health")
    @Authorize(action = "lending.intake", resource = "")
    @Operation(summary = "The caller's own four-pillar financial health (no score, edge only)")
    fun health(@HeaderParam(CustomerIntakeResource.PARTY_HEADER) partyHeader: String?): Uni<Response> {
        if (!callerIsPermitted()) {
            return Uni.createFrom().item(
                Response.status(HTTP_FORBIDDEN)
                    .entity(mapOf("error" to "caller is not the customer-edge intake principal")).build(),
            )
        }
        val partyId = scopeOf(partyHeader)
            ?: return Uni.createFrom().item(
                Response.status(HTTP_BAD_REQUEST)
                    .entity(mapOf("error" to "${CustomerIntakeResource.PARTY_HEADER} is missing or not a UUID"))
                    .build(),
            )

        return CoroutineScope(Dispatchers.Unconfined).async {
            val book = runCatchingUpstream { loans.findByParty(partyId).awaitSuspending() } ?: emptyList()
            val profile = runCatchingUpstream { profiles.profile(partyId).awaitSuspending() }
            val view = FinancialHealth.assess(
                FinancialHealthInputs(
                    monthlyIncome = profile?.incomeMonthly?.toDecimalOrNull(),
                    monthlyOutflow = profile?.outflowMonthly?.toDecimalOrNull(),
                    monthlyNet = profile?.netMonthly?.toDecimalOrNull(),
                    volatilityRatio = profile?.volatilityRatio?.toDecimalOrNull(),
                    // No balance source on this path yet, so the reserve pillar answers UNKNOWN.
                    // Deliberately left null rather than approximated from cashflow: a reserve the
                    // customer does not have, inferred from money they merely did not spend, is the
                    // single most dangerous number this screen could show.
                    liquidBalance = null,
                    monthlyDebtService = debtServiceOf(book),
                    hasArrears = book.takeIf { it.isNotEmpty() }?.any { it.status in ARREARS_STATES },
                    monthsObserved = profile?.monthsObserved,
                ),
            )
            Response.ok(view.pillars.map { it.toDto() }).build()
        }.asUni()
    }

    /**
     * Contractual monthly debt service: the next unpaid instalment of every live loan.
     *
     * Null when the party has no live loans — which is NOT the same as zero. Zero would let the
     * obligations pillar report a healthy DSTI for someone whose loans simply could not be read.
     */
    private suspend fun debtServiceOf(book: List<Loan>): BigDecimal? {
        val live = book.filter { it.status !in CLOSED_STATES }
        if (live.isEmpty()) return if (book.isEmpty()) BigDecimal.ZERO else null
        var total = BigDecimal.ZERO
        live.forEach { loan ->
            val schedule = runCatchingUpstream { installments.findByLoan(loan.id).awaitSuspending() } ?: return null
            val next = schedule.filter { !it.paid }.minByOrNull { it.number } ?: return@forEach
            total = total.add(next.payment.amount)
        }
        return total
    }

    private fun callerIsPermitted(): Boolean {
        if (!config.enabled) return false
        val permitted = config.callerPrincipal.orElse("")
        return permitted.isNotBlank() && identity.principal?.name == permitted
    }

    private fun scopeOf(partyHeader: String?): UUID? =
        partyHeader?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.takeIf { it != ZERO_UUID }

    /** Null on any upstream failure, so one slow service greys out one pillar and not the view. */
    private suspend fun <T> runCatchingUpstream(block: suspend () -> T): T? = runCatching { block() }.getOrElse { e ->
        if (e is CancellationException) throw e
        null
    }

    private fun String.toDecimalOrNull(): BigDecimal? =
        takeIf { it.isNotBlank() }?.let { runCatching { BigDecimal(it) }.getOrNull() }

    private fun HealthPillar.toDto() = CustomerHealthPillarDto(
        code = code,
        zone = zone.name,
        value = value?.toPlainString(),
        target = target?.toPlainString(),
    )

    companion object {
        private val ZERO_UUID = UUID(0, 0)
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_FORBIDDEN = 403

        private val ARREARS_STATES = setOf(
            LoanStatus.DELINQUENT,
            LoanStatus.DEFAULTED,
            LoanStatus.TERMINATION_NOTICED,
            LoanStatus.ACCELERATED,
        )

        private val CLOSED_STATES = setOf(
            LoanStatus.CLOSED,
            LoanStatus.SETTLED,
            LoanStatus.WRITTEN_OFF,
            LoanStatus.UNWOUND,
        )
    }
}

/**
 * One pillar on the wire. [value] and [target] are strings and are null for an UNKNOWN zone, so a
 * client has nothing invented to render.
 */
data class CustomerHealthPillarDto(val code: String, val zone: String, val value: String?, val target: String?)
