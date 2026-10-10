// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.returns.pension

import com.openbank.tax.application.port.out.ReturnDataPort
import com.openbank.tax.application.port.out.ReturnDataUnavailableException
import com.openbank.tax.domain.returns.Periodicity
import com.openbank.tax.domain.returns.ReportingPeriod
import com.openbank.tax.domain.returns.ReturnCatalogue
import com.openbank.tax.domain.returns.ReturnDefinition
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.WebApplicationException
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** Reads the two sources; split from the mapping so the mapping is testable without HTTP. */
interface PensionReportingSources {
    suspend fun fund(fundId: UUID, from: LocalDate, to: LocalDate): FundPeriodFiguresDto

    suspend fun participants(from: LocalDate, to: LocalDate): ParticipantAggregatesDto
}

@ApplicationScoped
class RestPensionReportingSources(
    @RestClient private val funds: PensionFundReportingClient,
    @RestClient private val pension: PensionReportingClient,
) : PensionReportingSources {
    override suspend fun fund(fundId: UUID, from: LocalDate, to: LocalDate) =
        sourced("pension-fund-service") { funds.periodFigures(fundId, from, to) }

    override suspend fun participants(from: LocalDate, to: LocalDate) =
        sourced("pension-service") { pension.participantAggregates(from, to) }

    /**
     * Any failure to obtain the figures is "unavailable" (503), never a zero and never a 500: a
     * provider's 409 (no published NAV backs the period, or the period books two currencies), a
     * 404 (an undeclared fund), a 401/403 (this client is not admitted) and a refused connection
     * all mean the same thing to the filer — this return cannot be assembled yet.
     */
    private suspend fun <T> sourced(source: String, call: suspend () -> T): T = try {
        call()
    } catch (e: WebApplicationException) {
        throw ReturnDataUnavailableException("$source answered ${e.response.status}: ${e.message}", e)
    } catch (e: ProcessingException) {
        throw ReturnDataUnavailableException("$source unreachable: ${e.message}", e)
    }
}

/**
 * The ČNB pension catalogue (`cz-pension-cnb`) mapped onto the providers' read models (#12425).
 *
 * Every datapoint is either taken from a sourced figure or the whole return is refused: there is
 * no default, no zero-fill and no partial return. Returns whose figures no service owns yet are
 * refused by name ([UNSOURCED]) with the reason, so the 503 says WHY, not just that.
 */
@ApplicationScoped
class PensionReturnDataAdapter(private val sources: PensionReportingSources) : ReturnDataPort {
    override val available: Boolean = true

    override suspend fun fetch(
        catalogue: ReturnCatalogue,
        definition: ReturnDefinition,
        entityId: String,
        period: ReportingPeriod,
    ): Map<String, BigDecimal> {
        if (catalogue.id != CATALOGUE_ID) {
            throw ReturnDataUnavailableException("No data source is bound for catalogue ${catalogue.id}")
        }
        UNSOURCED[definition.code]?.let { reason ->
            throw ReturnDataUnavailableException("${definition.code}: $reason")
        }
        val from = periodStart(period)
        val to = period.endDate
        if (definition.code == "PSP31-04") return companyFlows(sources.participants(from, to))
        val extract = FUND_RETURNS[definition.code]
            ?: throw ReturnDataUnavailableException("${definition.code}: no mapping to a source read model")
        return extract(definition.code, entityId, fund(entityId, from, to))
    }

    private fun companyFlows(a: ParticipantAggregatesDto): Map<String, BigDecimal> = mapOf(
        "contributions_participant_ytd" to a.contributionsYtd.participant,
        "contributions_employer_ytd" to a.contributionsYtd.employer,
        "contributions_state_ytd" to a.contributionsYtd.state,
        "contributions_total_ytd" to a.contributionsYtd.total,
        "payouts_total_ytd" to a.payoutsYtd.total,
        "payout_cases_ytd" to BigDecimal(a.payoutsYtd.cases),
        "pensioners_count" to BigDecimal(a.participants.pensioners),
    )

    private suspend fun fund(entityId: String, from: LocalDate, to: LocalDate): FundPeriodFiguresDto {
        val fundId = try {
            UUID.fromString(entityId)
        } catch (e: IllegalArgumentException) {
            throw ReturnDataUnavailableException(
                "fund entity '$entityId' is not a pension-fund-service fund id",
                e,
            )
        }
        return sources.fund(fundId, from, to)
    }

    companion object {
        const val CATALOGUE_ID = "cz-pension-cnb"

        private fun balanceSheet(f: FundPeriodFiguresDto) = mapOf(
            "total_assets" to f.balanceSheet.totalAssets,
            "total_liabilities" to f.balanceSheet.totalLiabilities,
            "total_equity" to f.balanceSheet.totalEquity,
        )

        private fun portfolio(code: String, entityId: String, f: FundPeriodFiguresDto): Map<String, BigDecimal> {
            val value = f.portfolio.carryingValue
            val count = f.portfolio.holdingsCount
            if (value == null || count == null) {
                throw ReturnDataUnavailableException(
                    "$code: the closing NAV for fund $entityId predates position recording — portfolio unknown",
                )
            }
            return mapOf("holdings_carrying_value" to value, "holdings_count" to BigDecimal(count))
        }

        /** Fund-scoped returns: return code -> datapoints taken from pension-fund-service's period figures. */
        private val FUND_RETURNS: Map<
            String,
            (
                String,
                String,
                FundPeriodFiguresDto,
            ) -> Map<String, BigDecimal>,
            > = mapOf(
            "PSP10-12-FUND" to { _, _, f -> balanceSheet(f) },
            "PEF12-04-FUND" to { _, _, f -> balanceSheet(f) },
            "PSP20-12-FUND" to { _, _, f ->
                mapOf(
                    "income_ytd" to f.profitAndLossYtd.income,
                    "expenses_ytd" to f.profitAndLossYtd.expenses,
                    "profit_loss_ytd" to f.profitAndLossYtd.profitLoss,
                )
            },
            "PSP30-12" to { _, _, f ->
                mapOf(
                    "units_opening" to f.units.opening,
                    "units_issued" to f.units.issued,
                    "units_cancelled" to f.units.cancelled,
                    "units_closing" to f.units.closing,
                    "unit_value" to f.units.unitValue,
                    "unit_value_period_max" to f.units.unitValuePeriodMax,
                    "fund_equity" to f.balanceSheet.totalEquity,
                )
            },
            "PSP34-12-FUND" to ::portfolio,
            "PEF14-04" to { _, _, f ->
                mapOf(
                    "pension_entitlements_opening" to f.entitlements.opening,
                    "entitlement_increase" to f.entitlements.increase,
                    "entitlement_decrease" to f.entitlements.decrease,
                    "pension_entitlements_closing" to f.entitlements.closing,
                )
            },
            "PEF15-01" to { _, _, f ->
                mapOf(
                    "participants_count" to BigDecimal(f.participants.holders),
                    "participants_contributing_count" to BigDecimal(f.participants.subscribing),
                )
            },
        )

        /**
         * Returns no service in this platform owns the figures for. The company's OWN balance
         * sheet, P&L, own portfolio, capital, organisation and dividends live in its accounting
         * system, not in either pension service (fund assets are segregated from the company's by
         * law, ADR-0334 §1); a fund's loans are not modelled at all.
         */
        val UNSOURCED: Map<String, String> = mapOf(
            "PSP10-12-PS" to "the pension company's own balance sheet has no source system in this platform",
            "PSP20-12-PS" to "the pension company's own P&L has no source system in this platform",
            "PSP34-12-PS" to "the pension company's own portfolio has no source system in this platform",
            "PEF12-04-PS" to "the pension company's own balance sheet has no source system in this platform",
            "PSP32-04" to "regulatory capital and its requirement have no source system in this platform",
            "PSP50-04" to "share capital, headcount and qualifying holders have no source system in this platform",
            "PSP40-01" to "dividend_paid_or_planned has no source system in this platform",
            "PEF13-04" to "fund loans are not modelled by pension-fund-service",
        )

        /** First day of the period [period] closes; YTD figures are computed by the providers. */
        fun periodStart(period: ReportingPeriod): LocalDate = when (period.periodicity) {
            Periodicity.MONTH -> YearMonth.from(period.endDate).atDay(1)
            Periodicity.QUARTER -> YearMonth.from(period.endDate).minusMonths(2).atDay(1)
            Periodicity.YEAR -> LocalDate.of(period.endDate.year, 1, 1)
        }
    }
}
