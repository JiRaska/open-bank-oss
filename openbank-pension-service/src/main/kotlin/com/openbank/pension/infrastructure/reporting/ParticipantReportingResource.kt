// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.reporting

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.pension.application.port.out.ParticipantReportingQueries
import com.openbank.pension.application.usecase.ParticipantReportingService
import com.openbank.pension.domain.reporting.ParticipantPeriodAggregates
import com.openbank.pension.domain.reporting.PayoutTotals
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException

@ApplicationScoped
class ParticipantReportingBeans {
    @Produces
    @ApplicationScoped
    fun participantReportingService(
        queries: ParticipantReportingQueries,
        @ConfigProperty(name = "openbank.pension.reporting.currency", defaultValue = "CZK") currency: String,
    ): ParticipantReportingService = ParticipantReportingService(queries, currency)
}

/**
 * Participant aggregates per period for the ČNB returns (#12425, ADR-0336 D4). Counts and sums
 * only — no contract, party or participant attribute is returned.
 *
 * `pension.reporting.aggregate` is served to staff and to tax-reporting-service's OWN client
 * (pension_rest_ext.rego), and excluded from `operator-read-any` in rules.yaml so the shared
 * service-account does not reach it. A period booked in more than one currency is a 409
 * (MixedCurrencyException is an IllegalStateException), never a sum across currencies.
 */
@Tag(name = "Reporting", description = "Participant aggregates for regulatory returns (#12425)")
@Path("/api/v1/pension/reporting")
@jakarta.ws.rs.Produces(MediaType.APPLICATION_JSON)
class ParticipantReportingResource {

    @Inject
    lateinit var reporting: ParticipantReportingService

    @GET
    @Path("/participant-aggregates")
    @Operation(summary = "Participant counts, contributions by source, payouts by form and transfers for one period")
    @RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN, Roles.COMPLIANCE, Roles.AUDITOR)
    @Authorize(action = "pension.reporting.aggregate")
    suspend fun participantAggregates(
        @QueryParam("periodStart") periodStart: String?,
        @QueryParam("periodEnd") periodEnd: String?,
    ): ParticipantAggregatesResponse = ParticipantAggregatesResponse.from(
        reporting.aggregates(date("periodStart", periodStart), date("periodEnd", periodEnd)),
    )

    private fun date(name: String, value: String?): LocalDate {
        requireNotNull(value) { "query parameter '$name' is required" }
        return try {
            LocalDate.parse(value)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("query parameter '$name' must be an ISO date (YYYY-MM-DD)", e)
        }
    }
}

data class ContributionsDto(
    val participant: BigDecimal,
    val employer: BigDecimal,
    val state: BigDecimal,
    val transferIn: BigDecimal,
    val total: BigDecimal,
)

data class PayoutLineDto(val amount: BigDecimal, val count: Long)

data class PayoutsDto(
    val total: BigDecimal,
    val cases: Long,
    val taxWithheld: BigDecimal,
    val byForm: Map<String, PayoutLineDto>,
) {
    companion object {
        fun from(p: PayoutTotals) = PayoutsDto(
            total = p.total.setScale(2),
            cases = p.cases,
            taxWithheld = p.taxWithheld,
            byForm = p.byForm.mapValues { (_, l) -> PayoutLineDto(l.amount, l.count) },
        )
    }
}

data class ParticipantsDto(
    val inForce: Long,
    val newInPeriod: Long,
    val exitedInPeriod: Long,
    val contributing: Long,
    val pensioners: Long,
    val byProductLine: Map<String, Long>,
    val byAgeBand: Map<String, Long>,
    val byStatusAtPeriodEnd: Map<String, Long>,
)

data class StateContributionsDto(val claimed: BigDecimal, val received: BigDecimal, val returned: BigDecimal)

data class TransfersDto(val inCount: Long, val inAmount: BigDecimal, val outCount: Long, val outAmount: BigDecimal)

data class ParticipantAggregatesResponse(
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val currency: String,
    val participants: ParticipantsDto,
    val contributions: ContributionsDto,
    val contributionsYtd: ContributionsDto,
    val stateContributions: StateContributionsDto,
    val payouts: PayoutsDto,
    val payoutsYtd: PayoutsDto,
    val transfers: TransfersDto,
) {
    companion object {
        fun from(a: ParticipantPeriodAggregates): ParticipantAggregatesResponse {
            fun c(t: com.openbank.pension.domain.reporting.ContributionTotals) =
                ContributionsDto(t.participant, t.employer, t.state, t.transferIn, t.total)
            val p = a.participants
            return ParticipantAggregatesResponse(
                periodStart = a.periodStart,
                periodEnd = a.periodEnd,
                currency = a.currency,
                participants = ParticipantsDto(
                    p.inForce,
                    p.newInPeriod,
                    p.exitedInPeriod,
                    p.contributing,
                    p.pensioners,
                    p.byProductLine,
                    p.byAgeBand,
                    p.byStatusAtPeriodEnd,
                ),
                contributions = c(a.contributions),
                contributionsYtd = c(a.contributionsYtd),
                stateContributions = StateContributionsDto(
                    a.stateContributions.claimed,
                    a.stateContributions.received,
                    a.stateContributions.returned,
                ),
                payouts = PayoutsDto.from(a.payouts),
                payoutsYtd = PayoutsDto.from(a.payoutsYtd),
                transfers = TransfersDto(
                    a.transfers.inCount,
                    a.transfers.inAmount,
                    a.transfers.outCount,
                    a.transfers.outAmount,
                ),
            )
        }
    }
}
