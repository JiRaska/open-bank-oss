// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.model

import java.math.BigDecimal

enum class CounterpartyKind { BANK, CENTRAL_BANK }

/**
 * Counterparty master (ADR-0315 D4). A limit is per counterparty AND per currency and caps the
 * outstanding principal the bank has PLACED with that counterparty — a borrowing is money the
 * bank owes, not credit it extends, so it consumes no limit.
 *
 * `synthetic = true` marks the sandbox counterparty set (ADR-0315 D9). The seed banks are
 * invented; the only real institution is the central bank itself.
 */
data class Counterparty(
    val id: String,
    val name: String,
    val kind: CounterpartyKind,
    val limits: Map<String, BigDecimal>,
    val synthetic: Boolean,
) {
    fun limitFor(currency: String): BigDecimal = limits[currency] ?: BigDecimal.ZERO
}

/**
 * The outcome of a limit check at `PENDING_APPROVAL` / approval. `exposureBefore` is the
 * counterparty's outstanding placed principal in [currency] excluding this deal.
 */
data class LimitCheck(
    val counterpartyId: String,
    val currency: String,
    val limit: BigDecimal,
    val exposureBefore: BigDecimal,
    val dealAmount: BigDecimal,
) {
    val exposureAfter: BigDecimal get() = exposureBefore + dealAmount
    val headroomAfter: BigDecimal get() = limit - exposureAfter
    val breached: Boolean get() = exposureAfter > limit

    companion object {
        /** A borrowing consumes no credit limit, so its check carries a zero deal amount. */
        fun of(counterparty: Counterparty, deal: Deal, exposureBefore: BigDecimal) = LimitCheck(
            counterpartyId = counterparty.id,
            currency = deal.currency,
            limit = counterparty.limitFor(deal.currency),
            exposureBefore = exposureBefore,
            dealAmount = if (deal.product.isAsset) deal.principal else BigDecimal.ZERO,
        )
    }
}
