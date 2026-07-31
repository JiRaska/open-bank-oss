// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.port.out

import com.openbank.lending.domain.model.Loan
import com.openbank.libs.domain.money.Money
import com.openbank.libs.lending.EclInputs
import io.smallrye.mutiny.Uni
import java.math.BigDecimal
import java.util.UUID

/**
 * Outbound integration ports for the lending bounded context (ADR-0028 D3/D4).
 *
 * Each has an offline-buildable `@Default` no-op binding (`NoOpLendingAdapters`) so the service builds
 * and boots with zero external dependency; real integrations land later as build-time-gated
 * `@Alternative @Priority` adapters, following the platform realization pattern (ADR-0045).
 */

/** A single ledger posting the loan book emits — it never mutates balances itself (ADR-0028 D3). */
data class LedgerPosting(val reference: String, val partyId: UUID, val amount: Money, val kind: PostingKind)

/**
 * The economic events the loan book posts to the ledger.
 *
 * Interest is recognized on an accrual basis: [INTEREST_ACCRUAL] books income against a receivable the
 * moment an installment falls due (the scheduled servicing pass), and [INTEREST_SETTLEMENT] clears that
 * receivable when cash arrives. [INTEREST] is the direct cash-basis recognition used only when an
 * installment is repaid *before* it has been accrued (early/on-time payment) — so interest income is
 * always recognized exactly once.
 *
 * [PROVISIONING] books the **delta** in IFRS 9 expected credit loss between one scheduled provisioning
 * cycle and the last one for the same loan (ADR-0028 Phase 3) — never the full ECL again. An increase
 * (more provision required) debits the loss-provision expense and credits the loan-loss allowance (a
 * contra-asset); a decrease (partial release) is the reverse. The loan principal GL is never touched by
 * a provisioning entry — provisioning is an impairment overlay, not a change to the recognized asset.
 *
 * [RESCHEDULE_FORGIVENESS] books a partial debt-relief amount granted as part of a loan restructuring
 * (issue #667/#668) — the same economic event as [WRITE_OFF] (a realized credit loss, asset off the
 * books), just partial rather than the full remaining exposure, kept as a distinct kind so an audit
 * trail can tell a restructuring's forgiveness apart from a full write-off even though both hit the
 * same GL accounts.
 *
 * [INTEREST_CAPITALIZATION] rolls an accrued-but-unpaid interest receivable into the restructured
 * principal when a reschedule discards the installment that carried it (#1245). The income is NOT
 * reversed: an installment can only be accrued once it has fallen due (`findAccruable` gates on
 * `dueDate <= asOf`), so that interest was genuinely earned — reversing it would silently forgive it,
 * bypassing [RESCHEDULE_FORGIVENESS], the one mechanism ADR-0028 gives debt relief so that it stays
 * explicit and auditable. So the receivable moves into the asset it is now part of, and the borrower
 * still owes it.
 *
 * [WRITE_OFF_INTEREST] derecognizes the accrued-but-unpaid interest receivable at write-off, alongside
 * the principal [WRITE_OFF]. Same premise as above — the income was earned when the installment fell
 * due, so it is not reversed; what failed is collection, and an uncollectible receivable is a credit
 * loss exactly like the principal exposure. Kept as a distinct kind so the audit trail can split a
 * write-off's loss into its principal and interest components.
 */
enum class PostingKind {
    DISBURSEMENT,
    PRINCIPAL_REPAYMENT,
    INTEREST,
    INTEREST_ACCRUAL,
    INTEREST_CAPITALIZATION,
    INTEREST_SETTLEMENT,
    WRITE_OFF,
    WRITE_OFF_INTEREST,
    PROVISIONING,
    RESCHEDULE_FORGIVENESS,
    WITHDRAWAL_UNWIND,
    EARLY_REPAYMENT_COMPENSATION,
    SETTLEMENT,
}

/** Posts loan cash events to the ledger (via the outbox in the real adapter). */
interface LedgerPostingPort {
    fun post(posting: LedgerPosting): Uni<Unit>
}

/** Creditworthiness signal from an external bureau / scoring source (EBA/GL/2020/06). */
data class CreditAssessment(
    val score: Int?, // null when no bureau data is available
    val hasAdverseData: Boolean,
    val source: String,
)

interface CreditBureauPort {
    fun assess(partyId: UUID, requestedAmount: Money): Uni<CreditAssessment>
}

/** Re-values collateral; the no-op returns the supplied market value unchanged. */
interface CollateralValuationPort {
    fun revalue(type: String, declaredValue: Money): Uni<Money>
}

/**
 * Supplies the IFRS 9 risk parameters (PD/LGD) for a loan. The pure ECL math (`libs.Ifrs9`) consumes
 * whatever this returns; swapping the conservative no-op for a real PD model is a wiring change only.
 */
interface RiskParameterSource {
    fun parametersFor(loan: Loan, exposureAtDefault: Money): Uni<EclInputs>

    companion object {
        /** Deliberately conservative defaults used by the no-op binding. */
        val DEFAULT_PD_12M: BigDecimal = BigDecimal("0.03")
        val DEFAULT_PD_LIFETIME: BigDecimal = BigDecimal("0.20")
        val DEFAULT_LGD: BigDecimal = BigDecimal("0.45")
    }
}
