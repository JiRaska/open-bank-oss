// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.model

import java.math.BigDecimal
import java.util.UUID

/**
 * Simulated SWIFT state (outgoing MT103 rail): every message the scenario drove through the
 * real `SwiftMessage.validate` gate and every ledger journal that actually landed for a message
 * whose money left, so the Layer-3 invariants can assert:
 *
 *  1. *completeness* — no message may still be mid-flight (PENDING/VALIDATED/SENT) once the
 *     step has settled; every message the scenario created reached a terminal status
 *     (COMPLETED / REJECTED / FAILED) inside its own step;
 *  2. *conservation* — Σ COMPLETED message amounts == Σ ledger journals posted for SWIFT
 *     traffic, so a message that completed without money leaving (or money that left without
 *     a completion) is caught, while REJECTED (validate refused) and FAILED (compensated)
 *     messages provably posted nothing.
 *
 * Posted amounts are keyed by message id, not summed fleet-wide, so the conservation check
 * stays per-message and a reversal cannot hide behind an unrelated posting.
 */
class SwiftBook {
    private val posted = mutableMapOf<UUID, BigDecimal>()

    /** Record the ledger journal actually posted for one SWIFT message that left. */
    fun recordPosted(messageId: UUID, amount: BigDecimal) {
        posted[messageId] = posted.getOrDefault(messageId, BigDecimal.ZERO) + amount
    }

    fun postedAmount(messageId: UUID): BigDecimal = posted.getOrDefault(messageId, BigDecimal.ZERO)

    fun postedIds(): Set<UUID> = posted.keys
}
