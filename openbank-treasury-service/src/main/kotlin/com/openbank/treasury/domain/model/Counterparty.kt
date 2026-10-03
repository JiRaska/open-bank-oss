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
        /**
         * A borrowing consumes no credit limit, so its check carries a zero deal amount. An FX spot
         * is checked against the CZK limit by its CZK equivalent ([Deal.limitCurrency]/[Deal.limitAmount]).
         */
        fun of(counterparty: Counterparty, deal: Deal, exposureBefore: BigDecimal) = LimitCheck(
            counterpartyId = counterparty.id,
            currency = deal.limitCurrency,
            limit = counterparty.limitFor(deal.limitCurrency),
            exposureBefore = exposureBefore,
            dealAmount = deal.limitAmount,
        )
    }
}

/** The figures of one limit check, as recorded on a submit/approve transition. */
data class LimitSnapshot(
    val limit: BigDecimal,
    val currency: String,
    val exposureAfter: BigDecimal,
    val headroomAfter: BigDecimal,
)

/**
 * The limit note a submit/approve transition carries. The note is persisted as text, so [parse] is
 * the single reader of the format [format] writes — the API derives structured figures from it
 * instead of every client re-parsing English prose.
 */
object LimitNote {
    private val PATTERN =
        Regex(
            """^limit (?<limit>-?[0-9.]+) (?<ccy>[A-Z]{3}), exposure after (?<after>-?[0-9.]+), headroom (?<headroom>-?[0-9.]+)$""",
        )

    fun format(check: LimitCheck): String =
        "limit ${check.limit} ${check.currency}, exposure after ${check.exposureAfter}, headroom ${check.headroomAfter}"

    /** The figures in a note [format] wrote, or null for any other note (a reason, a reference). */
    fun parse(note: String?): LimitSnapshot? {
        val m = note?.let { PATTERN.matchEntire(it.trim()) } ?: return null
        fun group(name: String) = m.groups[name]!!.value
        return LimitSnapshot(
            BigDecimal(group("limit")),
            group("ccy"),
            BigDecimal(group("after")),
            BigDecimal(group("headroom")),
        )
    }
}
