// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.vop.domain.model

import java.time.Instant

/**
 * The four Verification-of-Payee outcomes (ADR-0171, IPR Art. 5c). These mirror the EPC VoP
 * scheme's `MTCH` / `CMTC` / `NMTC` / `NOAP` and serialise to the wire values the admin UI's
 * `VopStatus` union already expects.
 */
enum class VopOutcome {
    /** The supplied name and the account-holder name are the same name. */
    MATCH,

    /** Near-miss the payer can plausibly correct — reordered tokens, an initial, a legal-form
     *  suffix, or a one-character typo. The actual name MAY be returned (ADR-0171 §5). */
    CLOSE_MATCH,

    /** Both names are known and they are not the same name. The actual name is NEVER returned. */
    NO_MATCH,

    /** No answer is available — the IBAN is unknown to us, no name is held, or the lookup failed.
     *  Never conflate with [MATCH]: absence of an answer is not a positive one. */
    NO_DATA,
}

/**
 * Why a [VopOutcome.NO_DATA] was returned. The payer is told *that* we could not answer; this
 * enum records *why*, for operators and for the evidence record.
 */
enum class VopNoDataReason {
    /** The IBAN is not ours and we have no EPC VoP scheme link to ask the payee's PSP (ADR-0171 §4). */
    NO_SCHEME_CONNECTIVITY,

    /** Ours, but no account matches the IBAN. */
    ACCOUNT_NOT_FOUND,

    /** The account exists but no holder name is held against it. */
    NAME_NOT_AVAILABLE,

    /** A downstream lookup failed. Fail-open with a warning (ADR-0171 §3) — never a silent MATCH. */
    LOOKUP_UNAVAILABLE,
}

/**
 * A completed verification. [matchedName] is populated only for [VopOutcome.CLOSE_MATCH] — the
 * asymmetry that stops VoP becoming an account-holder-name disclosure oracle (ADR-0171 §5).
 */
data class VopVerification(
    val outcome: VopOutcome,
    val noDataReason: VopNoDataReason? = null,
    val matchedName: String? = null,
    val verifiedAt: Instant,
) {
    init {
        require(outcome == VopOutcome.CLOSE_MATCH || matchedName == null) {
            "matchedName may only be disclosed for CLOSE_MATCH (ADR-0171 §5), not for $outcome"
        }
        require((outcome == VopOutcome.NO_DATA) == (noDataReason != null)) {
            "noDataReason must be set iff the outcome is NO_DATA, but outcome=$outcome reason=$noDataReason"
        }
    }
}
