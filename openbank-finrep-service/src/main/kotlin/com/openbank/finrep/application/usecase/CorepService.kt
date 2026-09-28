// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.application.usecase

import com.openbank.finrep.application.port.inbound.CorepUseCase
import com.openbank.finrep.application.port.inbound.GetCorepTemplateQuery
import com.openbank.finrep.application.port.inbound.TrialBalanceEvidence
import com.openbank.finrep.application.port.out.FinrepMetricsPort
import com.openbank.finrep.application.port.out.LedgerPort
import com.openbank.finrep.application.port.out.RegulatoryFramework
import com.openbank.finrep.application.port.out.RiskCapitalPort
import com.openbank.finrep.application.port.out.RiskLiquidityPort
import com.openbank.finrep.application.port.out.TemplateFailureReason
import com.openbank.finrep.application.port.out.TemplateRender
import com.openbank.finrep.application.port.out.TrialBalanceSnapshot
import com.openbank.finrep.domain.mapper.C0100Mapper
import com.openbank.finrep.domain.mapper.C0200Mapper
import com.openbank.finrep.domain.mapper.C7200Mapper
import com.openbank.finrep.domain.mapper.C7300Mapper
import com.openbank.finrep.domain.model.CorepTemplate
import jakarta.enterprise.context.ApplicationScoped
import java.time.Duration
import java.time.LocalDate

/**
 * COREP report generation (ADR-0097 Phase 2). C 01.00 (Own Funds) is mapped from the ledger's
 * trial balance; C 02.00 (Own Funds Requirements) from the risk engine's Pillar 1 result for a
 * TIED_OUT snapshot at the report date (ADR-0313 D6); C 72.00 (LCR liquid assets) from the same
 * snapshot's LCR liquid-asset result; C 73.00 (LCR outflows) from that same LCR result. Every other COREP template (C 05.01
 * transitional provisions, etc.) is out of scope.
 *
 * The rendered return deliberately carries **flagged data gaps** rather than silent omissions
 * (ADR-0097): a render with no recognised 6000-6060 capital source is reported as explicit zeros
 * marked `isDataGap`. Once those ledger accounts carry balances, the mapper derives the own-funds
 * subtotals and `data_gap_cells` proves that the source gap cleared.
 */
@ApplicationScoped
class CorepService(
    private val ledgerPort: LedgerPort,
    private val metrics: FinrepMetricsPort,
    private val riskCapital: RiskCapitalPort,
    private val riskLiquidity: RiskLiquidityPort,
) : CorepUseCase {

    override suspend fun getTemplate(query: GetCorepTemplateQuery): CorepTemplate {
        val startedAt = System.nanoTime()
        var trialBalanceLines = 0
        val template = when (query.templateId) {
            "C_01.00" -> trialBalance(query.asOf, query.evidence).let {
                trialBalanceLines = it.lines.size
                C0100Mapper.map(it.lines, query.asOf)
            }
            C0200Mapper.TEMPLATE_ID -> C0200Mapper.map(capital(query.asOf), query.asOf)
            C7200Mapper.TEMPLATE_ID -> C7200Mapper.map(liquidity(query.asOf), query.asOf)
            C7300Mapper.TEMPLATE_ID -> C7300Mapper.map(liquidity(query.asOf), query.asOf)
            else -> {
                metrics.templateFailed(RegulatoryFramework.COREP, TemplateFailureReason.UNKNOWN_TEMPLATE)
                throw IllegalArgumentException("Unknown or unimplemented COREP template: ${query.templateId}")
            }
        }
        metrics.templateRendered(
            TemplateRender(
                framework = RegulatoryFramework.COREP,
                templateId = template.templateId,
                trialBalanceLines = trialBalanceLines,
                cells = template.cells.size,
                dataGapCells = template.cells.count { it.isDataGap },
                // COREP defines no balance-sheet identity, so "balanced" is neither true nor false.
                balanced = null,
                // ... and therefore no cross-check verdict either: there is nothing to agree with.
                balanceVerdict = null,
                duration = Duration.ofNanos(System.nanoTime() - startedAt),
            ),
        )
        return template
    }

    /** Count-and-rethrow, as for the ledger below: no Pillar 1 report can be produced without it. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun capital(asOf: LocalDate) = try {
        riskCapital.capitalAt(asOf)
    } catch (e: Exception) {
        metrics.templateFailed(RegulatoryFramework.COREP, TemplateFailureReason.RISK_ENGINE_UNAVAILABLE)
        throw e
    }

    /** Count-and-rethrow, as for capital: no C 72.00 can be produced without the risk engine. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun liquidity(asOf: LocalDate) = try {
        riskLiquidity.liquidityAt(asOf)
    } catch (e: Exception) {
        metrics.templateFailed(RegulatoryFramework.COREP, TemplateFailureReason.RISK_ENGINE_UNAVAILABLE)
        throw e
    }

    /**
     * Count-and-rethrow: an unreachable ledger means no regulatory report can be produced at all,
     * which the caller must still see as a failure. The catch is deliberately broad because every
     * REST-client failure mode (connect, timeout, 5xx, deserialisation) means the same thing here.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun trialBalance(asOf: LocalDate, evidence: TrialBalanceEvidence): TrialBalanceSnapshot = try {
        when (evidence) {
            TrialBalanceEvidence.FROZEN -> ledgerPort.getTrialBalance(asOf)
            TrialBalanceEvidence.LIVE_PREVIEW -> ledgerPort.getLiveTrialBalance(asOf)
        }
    } catch (e: Exception) {
        metrics.templateFailed(RegulatoryFramework.COREP, TemplateFailureReason.LEDGER_UNAVAILABLE)
        throw e
    }
}
