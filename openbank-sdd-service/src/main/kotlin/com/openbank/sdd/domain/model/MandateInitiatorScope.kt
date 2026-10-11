// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sdd.domain.model

/**
 * Which mandates an authenticated caller may register or cancel (ADR-0335 D6).
 */
sealed interface MandateInitiatorScope {
    /** Staff and the customer edge: unchanged behaviour. */
    data object General : MandateInitiatorScope

    /**
     * A declared scoped initiator (rules.yaml `scoped_payment_initiators`): only mandates whose
     * creditor is [creditorIdentifier], and — on register — only on a debtor account the stated
     * subject party owns and holds active. A null identifier (unconfigured) permits nothing.
     */
    data class Scoped(val principal: String, val creditorIdentifier: String?) : MandateInitiatorScope {
        fun ownsCreditor(candidate: String): Boolean =
            creditorIdentifier != null && creditorIdentifier.equals(candidate.trim(), ignoreCase = true)
    }
}
