// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.domain.model

/**
 * Which population of journal activity an aggregate is computed over (ADR-0252 phase 1, #8615).
 *
 * The synthetic taint exists so that a bank-owned canary can post a REAL journal — exercising the
 * real posting path, the real screening path, the real event streams — without its money reaching
 * a regulatory return. That is only expressible once the aggregate can say which population it
 * meant, which is what this enum is.
 *
 * ## The default is [REAL_ONLY], and that is the whole safety argument
 *
 * ADR-0252 names two shapes for this: a dimension (this one) or dedicated general-ledger accounts.
 * The stated risk of the dimension is a consumer that forgets to filter and silently publishes
 * synthetic money inside a regulatory return. That risk is a property of the DEFAULT, not of the
 * dimension: every entry point here defaults to [REAL_ONLY], so a caller that forgets gets the
 * regulator-safe answer and only an explicit, written-down request can widen it. Including
 * synthetic activity is the thing you have to ask for.
 *
 * ## What it must NOT reach
 *
 * Nothing in a control path. Sanctions screening, Verification of Payee, SCA, limits and fraud
 * scoring always run for synthetic traffic — a canary that skips the controls proves nothing about
 * them (see `SyntheticTaint`). Inside the ledger the same rule keeps this enum off the deposit-
 * control tie-out and the sub-ledger balances: those reconcile the GL against the per-customer
 * analytical record, both sides read from the same journal lines, and a synthetic customer's
 * balance is genuinely owed to that synthetic customer. Filtering one side of a reconciliation
 * would manufacture a break, not prevent one.
 */
enum class LedgerScope {
    /** Real customer activity only — the regulatory population, and the default everywhere. */
    REAL_ONLY,

    /** Canary activity only. For reconciling what the synthetic fleet itself did. */
    SYNTHETIC_ONLY,

    /** Both. The ledger's own internal view; never a regulatory return. */
    ALL,
    ;

    companion object {
        /**
         * Parse a caller-supplied `scope` selector. Absent or blank means [REAL_ONLY] — the safe
         * answer for a caller who forgot. An unrecognised value throws rather than falling back:
         * a typo'd `?scope=al` silently answering real-only would hand a caller data they did not
         * ask for and cannot tell apart from data they did. libs-runtime maps
         * `IllegalArgumentException` to 400.
         */
        fun parse(value: String?): LedgerScope {
            val raw = value?.trim().orEmpty()
            if (raw.isEmpty()) return REAL_ONLY
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "unknown scope '$raw'; expected one of ${entries.joinToString(", ") { it.name }}",
                )
        }
    }
}
