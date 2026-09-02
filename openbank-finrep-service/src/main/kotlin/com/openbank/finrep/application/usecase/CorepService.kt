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
import com.openbank.finrep.application.port.out.TemplateFailureReason
import com.openbank.finrep.application.port.out.TemplateRender
import com.openbank.finrep.application.port.out.TrialBalanceSnapshot
import com.openbank.finrep.domain.mapper.C0100Mapper
import com.openbank.finrep.domain.model.CorepTemplate
import jakarta.enterprise.context.ApplicationScoped
import java.time.Duration
import java.time.LocalDate

/**
 * COREP report generation (ADR-0097 Phase 2, first increment). Only C 01.00 (Own Funds) is
 * implemented; every other COREP template (C 02.00 own funds requirements, C 05.01 transitional
 * provisions, etc.) is out of scope for this increment.
 *
 * The rendered return deliberately carries **flagged data gaps** rather than silent omissions
 * (ADR-0097): a render with no recognised 6000-6060 capital source is reported as explicit zeros
 * marked `isDataGap`. Once those ledger accounts carry balances, the mapper derives the own-funds
 * subtotals and `data_gap_cells` proves that the source gap cleared.
 */
@ApplicationScoped
class CorepService(private val ledgerPort: LedgerPort, private val metrics: FinrepMetricsPort) : CorepUseCase {

    override suspend fun getTemplate(query: GetCorepTemplateQuery): CorepTemplate {
        val startedAt = System.nanoTime()
        val snapshot = trialBalance(query.asOf, query.evidence)
        val template = when (query.templateId) {
            "C_01.00" -> C0100Mapper.map(snapshot.lines, query.asOf)
            else -> {
                metrics.templateFailed(RegulatoryFramework.COREP, TemplateFailureReason.UNKNOWN_TEMPLATE)
                throw IllegalArgumentException("Unknown or unimplemented COREP template: ${query.templateId}")
            }
        }
        metrics.templateRendered(
            TemplateRender(
                framework = RegulatoryFramework.COREP,
                templateId = template.templateId,
                trialBalanceLines = snapshot.lines.size,
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
