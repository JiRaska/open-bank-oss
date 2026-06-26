// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.sdd.domain.authorise

import com.openbank.sdd.domain.model.MandateStatus
import com.openbank.sdd.domain.model.SddMandate
import com.openbank.sdd.domain.model.SddScheme
import com.openbank.sdd.domain.model.SequenceType
import java.math.BigDecimal
import java.time.LocalDate

/** An inbound collection instruction presented for authorisation against a stored mandate. */
data class CollectionInstruction(
    val creditorIdentifier: String,
    val umr: String,
    val scheme: SddScheme,
    val sequenceType: SequenceType,
    val amount: BigDecimal,
    val currency: String,
    val dueDate: LocalDate,
)

/** Debtor-set controls (PSD2 Art. 79 blocking/limiting). All optional; defaults are permissive. */
data class DebtorControls(
    val blockAll: Boolean = false,
    val blockedCreditors: Set<String> = emptySet(),
    val maxAmountPerCollection: BigDecimal? = null,
)

/**
 * The fail-closed authorisation decision (ADR-0036 §C).
 *  - [Reject] — the collection cannot be processed (no/invalid mandate, scheme/verification fault);
 *    a bank-side technical rejection.
 *  - [Refuse] — the mandate is fine but the debtor exercised a control (block/cap); a debtor refusal.
 *  - [Accept] — proceed; the caller delegates the actual debit to the ledger/payment path.
 */
sealed interface AuthorisationResult {
    data object Accept : AuthorisationResult
    data class Reject(val reasonCode: String, val reason: String) : AuthorisationResult
    data class Refuse(val reasonCode: String, val reason: String) : AuthorisationResult
}

/**
 * Pure collection-authorisation policy (ADR-0036 §C). Evaluated in order: mandate present & ACTIVE →
 * scheme match → EUR-only → B2B mandate verified → sequence coherence (one-off reuse) → debtor
 * controls. Mandate faults [Reject]; debtor controls [Refuse]. EPC reason codes are attached.
 */
object CollectionAuthorisationPolicy {

    fun authorise(
        mandate: SddMandate?,
        instruction: CollectionInstruction,
        controls: DebtorControls = DebtorControls(),
    ): AuthorisationResult {
        if (mandate == null) {
            return AuthorisationResult.Reject("MD01", "No valid mandate for ${instruction.umr}")
        }
        if (mandate.status != MandateStatus.ACTIVE) {
            return AuthorisationResult.Reject("MD01", "Mandate not active (${mandate.status})")
        }
        if (mandate.scheme != instruction.scheme) {
            return AuthorisationResult.Reject("MD01", "Scheme mismatch: mandate ${mandate.scheme} vs ${instruction.scheme}")
        }
        if (instruction.currency != "EUR") {
            return AuthorisationResult.Reject("FF05", "SEPA Direct Debit is EUR-only (got ${instruction.currency})")
        }
        if (mandate.scheme == SddScheme.B2B && !mandate.b2bConfirmed) {
            return AuthorisationResult.Reject("MD01", "B2B mandate not verified by debtor bank")
        }
        if (mandate.sequenceType == SequenceType.OOFF && mandate.lastCollectionDate != null) {
            return AuthorisationResult.Reject("MD01", "One-off mandate already used")
        }
        if (controls.blockAll) {
            return AuthorisationResult.Refuse("MS02", "Debtor has blocked all direct debits")
        }
        if (instruction.creditorIdentifier in controls.blockedCreditors) {
            return AuthorisationResult.Refuse("MS02", "Creditor ${instruction.creditorIdentifier} is on the debtor block-list")
        }
        val cap = controls.maxAmountPerCollection
        if (cap != null && instruction.amount > cap) {
            return AuthorisationResult.Refuse("MS02", "Amount ${instruction.amount} exceeds debtor cap $cap")
        }
        return AuthorisationResult.Accept
    }
}
