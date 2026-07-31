// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.port.`in`

import com.openbank.lending.domain.model.ForbearanceAssessment
import com.openbank.lending.domain.model.Loan
import com.openbank.libs.domain.identifiers.LoanId
import com.openbank.libs.lending.SettlementQuote
import io.smallrye.mutiny.Uni

/**
 * Termination and early-exit lifecycle (ADR-0215). Every exit is guarded by
 * `LoanTerminationPolicy`, evidenced (`credit.loan.transition`) and posted through the
 * existing ledger path. [actor] is the trusted JWT subject; bank-initiated termination
 * is four-eyes (maker != checker).
 */
interface TerminateLoanUseCase {

    /** Issue a binding settlement quote (ACTIVE → SETTLEMENT_QUOTED) and persist it with its validity window. */
    fun requestSettlementQuote(loanId: LoanId, actor: String): Uni<SettlementQuote>

    /** Settle against a still-valid quote; refuses an expired one (fail-closed, ADR-0215 D2). */
    fun settle(loanId: LoanId, actor: String): Uni<Loan>

    /** Statutory withdrawal inside the pack's cooling-off window (WITHDRAWN → UNWOUND with the unwind journal). */
    fun withdraw(loanId: LoanId, actor: String): Uni<Loan>

    /** ACTIVE → DELINQUENT when the oldest unpaid installment is past due. */
    fun markDelinquent(loanId: LoanId, actor: String): Uni<Loan>

    /** DELINQUENT → DEFAULTED when DPD crosses the pack's CRR Art. 178 threshold. */
    fun markDefaulted(loanId: LoanId, actor: String): Uni<Loan>

    /** Record the mandatory forbearance assessment (DEFAULTED → FORBEARANCE_ASSESSED). */
    fun recordForbearance(loanId: LoanId, assessment: ForbearanceAssessment, actor: String): Uni<Loan>

    /** Bank termination, maker step: the ground must be permitted by the pinned pack (fail-closed). */
    fun proposeTermination(loanId: LoanId, ground: String, actor: String): Uni<Loan>

    /** Bank termination, checker step (must differ from maker): TERMINATION_NOTICED with the pack's notice period. */
    fun decideTermination(loanId: LoanId, approve: Boolean, actor: String): Uni<Loan>

    /** Accelerate after the notice period elapses (full balance falls due). */
    fun accelerate(loanId: LoanId, actor: String): Uni<Loan>

    /** The current settlement quote for a loan, if any. */
    fun latestQuote(loanId: LoanId): Uni<com.openbank.lending.infrastructure.persistence.entity.SettlementQuoteEntity?>
}
