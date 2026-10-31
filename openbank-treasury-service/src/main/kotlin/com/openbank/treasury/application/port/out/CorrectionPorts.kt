// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.port.out

/**
 * The counterparty headroom the approval was vetted against: the limit (or the senior override's
 * bound) less the corrected deal's own amount. A deal booked meanwhile that eats into it refuses
 * the approval (422 LIMIT_BREACHED) instead of committing a breach.
 */
data class ExposureGuard(val counterpartyId: String, val currency: String, val maxOthers: java.math.BigDecimal) {
    companion object {
        /**
         * The guard of a limit-consuming write, from the SAME figures [com.openbank.treasury.domain.model.LimitCheck]
         * used: the line is (counterparty, the deal's LIMIT currency — CZK for an FX spot), and the
         * others may consume at most the limit (or a senior override's bound) less the deal's own
         * LIMIT amount. Booking, submission, approval and correction all build it here, so every
         * write that consumes a line is serialised on one key and measured by one query.
         */
        fun of(check: com.openbank.treasury.domain.model.LimitCheck, coversUpTo: java.math.BigDecimal? = null) =
            ExposureGuard(check.counterpartyId, check.currency, (coversUpTo ?: check.limit) - check.dealAmount)
    }
}

/** The counterparty exposure grew past the vetted headroom between the vetting and the lock (422 LIMIT_BREACHED). */
class CorrectionLimitBreachedException(message: String) : RuntimeException(message)
