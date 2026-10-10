// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.pensionfund.application.usecase.FundReportingService
import com.openbank.pensionfund.domain.model.FundPeriodReport
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Period-end fund figures for statutory reporting (#12425, ADR-0336 D4). Aggregate only.
 *
 * `pension-fund.reporting.read` is served to staff and to tax-reporting-service's OWN client
 * (pension_fund_rest_ext.rego); it is excluded from `operator-read-any` in rules.yaml, so the
 * shared service-account does not reach it.
 */
@Tag(name = "Reporting", description = "Period-end fund aggregates for regulatory returns (#12425)")
@Path("/api/v1/reporting/funds")
@Produces(MediaType.APPLICATION_JSON)
class FundReportingResource {

    @Inject
    lateinit var reporting: FundReportingService

    @GET
    @Path("/{fundId}/period-figures")
    @Operation(summary = "A fund's balance sheet, YTD P&L, unit roll-forward, portfolio and flows for one period")
    @RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN, Roles.COMPLIANCE, Roles.AUDITOR)
    @Authorize(action = "pension-fund.reporting.read", resource = "#fundId")
    suspend fun periodFigures(
        @PathParam("fundId") fundId: UUID,
        @QueryParam("periodStart") periodStart: String?,
        @QueryParam("periodEnd") periodEnd: String?,
    ): FundPeriodFiguresResponse {
        val start = date("periodStart", periodStart)
        val end = date("periodEnd", periodEnd)
        return FundPeriodFiguresResponse.from(reporting.periodReport(fundId, start, end))
    }

    private fun date(name: String, value: String?): LocalDate {
        requireNotNull(value) { "query parameter '$name' is required" }
        return try {
            LocalDate.parse(value)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("query parameter '$name' must be an ISO date (YYYY-MM-DD)", e)
        }
    }
}

data class BalanceSheetDto(val totalAssets: BigDecimal, val totalLiabilities: BigDecimal, val totalEquity: BigDecimal)

data class ProfitAndLossDto(val income: BigDecimal, val expenses: BigDecimal, val profitLoss: BigDecimal)

data class UnitsDto(
    val opening: BigDecimal,
    val issued: BigDecimal,
    val cancelled: BigDecimal,
    val closing: BigDecimal,
    val unitValue: BigDecimal,
    val unitValuePeriodMax: BigDecimal,
)

data class PortfolioDto(val carryingValue: BigDecimal?, val holdingsCount: Int?, val cash: BigDecimal?)

data class FlowsDto(
    val subscriptions: BigDecimal,
    val redemptions: BigDecimal,
    val switchesIn: BigDecimal,
    val switchesOut: BigDecimal,
    val unitFees: BigDecimal,
    val managementFeesAccrued: BigDecimal,
)

data class EntitlementsDto(
    val opening: BigDecimal,
    val increase: BigDecimal,
    val decrease: BigDecimal,
    val closing: BigDecimal,
)

data class ParticipantCountsDto(val holders: Int, val subscribing: Int)

data class FundPeriodFiguresResponse(
    val fundId: UUID,
    val currency: String,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val closingNavId: UUID,
    val closingValuationDate: LocalDate,
    val balanceSheet: BalanceSheetDto,
    val profitAndLossYtd: ProfitAndLossDto,
    val units: UnitsDto,
    val portfolio: PortfolioDto,
    val flows: FlowsDto,
    val entitlements: EntitlementsDto,
    val participants: ParticipantCountsDto,
    val basisNavIds: List<UUID>,
    val fingerprint: String,
) {
    companion object {
        fun from(r: FundPeriodReport) = FundPeriodFiguresResponse(
            fundId = r.fundId,
            currency = r.currency,
            periodStart = r.periodStart,
            periodEnd = r.periodEnd,
            closingNavId = r.closingNavId,
            closingValuationDate = r.closingValuationDate,
            balanceSheet = BalanceSheetDto(
                r.balanceSheet.totalAssets,
                r.balanceSheet.totalLiabilities,
                r.balanceSheet.totalEquity,
            ),
            profitAndLossYtd = ProfitAndLossDto(
                r.profitAndLossYtd.income,
                r.profitAndLossYtd.expenses,
                r.profitAndLossYtd.profitLoss,
            ),
            units = UnitsDto(
                r.units.opening,
                r.units.issued,
                r.units.cancelled,
                r.units.closing,
                r.units.unitValue,
                r.units.unitValuePeriodMax,
            ),
            portfolio = PortfolioDto(r.portfolio.carryingValue, r.portfolio.holdingsCount, r.portfolio.cash),
            flows = FlowsDto(
                r.flows.subscriptions,
                r.flows.redemptions,
                r.flows.switchesIn,
                r.flows.switchesOut,
                r.flows.unitFees,
                r.managementFeesAccrued,
            ),
            entitlements = EntitlementsDto(
                r.entitlements.opening,
                r.entitlements.increase,
                r.entitlements.decrease,
                r.entitlements.closing,
            ),
            participants = ParticipantCountsDto(r.participants.holders, r.participants.subscribing),
            basisNavIds = r.basisNavIds,
            fingerprint = r.fingerprint,
        )
    }
}
