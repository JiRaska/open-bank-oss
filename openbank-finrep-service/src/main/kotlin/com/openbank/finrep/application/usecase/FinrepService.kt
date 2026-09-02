// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.application.usecase

import com.openbank.finrep.application.port.inbound.FinrepUseCase
import com.openbank.finrep.application.port.inbound.GetFinrepTemplateQuery
import com.openbank.finrep.application.port.inbound.TrialBalanceEvidence
import com.openbank.finrep.application.port.out.FinrepMetricsPort
import com.openbank.finrep.application.port.out.LedgerPort
import com.openbank.finrep.application.port.out.RegulatoryFramework
import com.openbank.finrep.application.port.out.TemplateFailureReason
import com.openbank.finrep.application.port.out.TemplateRender
import com.openbank.finrep.application.port.out.TrialBalanceSnapshot
import com.openbank.finrep.domain.mapper.F0101Mapper
import com.openbank.finrep.domain.mapper.F0102Mapper
import com.openbank.finrep.domain.mapper.F0103Mapper
import com.openbank.finrep.domain.mapper.F0200Mapper
import com.openbank.finrep.domain.model.FinrepTemplate
import jakarta.enterprise.context.ApplicationScoped
import java.time.Duration
import java.time.LocalDate

/**
 * FINREP template rendering (ADR-0097 Phase 1). A pure derivation over the ledger trial balance — it
 * stores nothing and emits nothing, so **every** way it can be wrong still produces a well-formed 200
 * response. [FinrepMetricsPort] is what makes those ways visible: an empty trial balance renders a
 * template of honest-looking zeros, and a trial balance that does not satisfy double entry is
 * reported on `FinrepTemplate.isBalanced` and on the `balanced` tag of the render counter.
 *
 * That sentence used to read "is currently computed into `FinrepTemplate.isBalanced`, serialised,
 * and never looked at again". The second half stopped being true when admin-ui's export gate began
 * blocking on it (#5904); the FIRST half was never true — both mappers passed the literal `true`,
 * so the `balanced` tag had exactly one reachable value and an alert on it could not fire. Fixed in
 * #5987: the flag is now `TrialBalanceIdentity.holds(lines)`, which is falsifiable by input the
 * mappers do not otherwise read.
 */
@ApplicationScoped
class FinrepService(private val ledgerPort: LedgerPort, private val metrics: FinrepMetricsPort) : FinrepUseCase {

    override suspend fun getTemplate(query: GetFinrepTemplateQuery): FinrepTemplate {
        val startedAt = System.nanoTime()
        val snapshot = trialBalance(query.asOf, query.evidence)
        val template = when (query.templateId) {
            "F01.01" -> F0101Mapper.map(snapshot, query.asOf)
            "F01.02" -> F0102Mapper.map(snapshot, query.asOf)
            "F01.03" -> F0103Mapper.map(snapshot, query.asOf)
            "F02.00" -> F0200Mapper.map(snapshot, query.asOf)
            else -> {
                metrics.templateFailed(RegulatoryFramework.FINREP, TemplateFailureReason.UNKNOWN_TEMPLATE)
                throw IllegalArgumentException("Unknown FINREP template: ${query.templateId}")
            }
        }
        metrics.templateRendered(
            TemplateRender(
                framework = RegulatoryFramework.FINREP,
                templateId = template.templateId,
                trialBalanceLines = snapshot.lines.size,
                cells = template.cells.size,
                dataGapCells = template.dataGaps.size,
                balanced = template.isBalanced,
                balanceVerdict = template.balanceVerdict,
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
        metrics.templateFailed(RegulatoryFramework.FINREP, TemplateFailureReason.LEDGER_UNAVAILABLE)
        throw e
    }
}
