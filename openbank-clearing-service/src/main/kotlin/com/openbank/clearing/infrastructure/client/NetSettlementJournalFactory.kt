// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearing.infrastructure.client

import com.openbank.clearing.application.port.out.NetSettlementPosting
import java.util.UUID

/**
 * Builds the balanced double-entry journal for one batch's net-settlement leg (ADR-0281):
 *
 *  - **DEBIT**  Customer Cash Clearing {CCY} — clears the obligation the batch's payment legs
 *    accrued against the scheme while the batch was IN_CLEARING;
 *  - **CREDIT** Scheme Settlement {CCY} — the money leaves the bank's settlement account at the
 *    scheme (the nostro-at-scheme asset).
 *
 * The GL ids are the deterministic seed ids from ledger migrations V3/V14 (cash-clearing) and
 * V26 (scheme-settlement) — the chart-of-accounts convention this fleet already relies on from
 * `PaymentJournalFactory`, so no lookup call is needed at posting time.
 */
object NetSettlementJournalFactory {

    // ledger V3 + V14 — Customer Cash Clearing, per currency.
    private val CASH_CLEARING_GL = mapOf(
        "CZK" to UUID.fromString("a0000000-0000-0000-0000-000000000001"),
        "EUR" to UUID.fromString("a0000000-0000-0000-0000-000000001101"),
        "USD" to UUID.fromString("a0000000-0000-0000-0000-000000001102"),
        "GBP" to UUID.fromString("a0000000-0000-0000-0000-000000001103"),
    )

    // ledger V26 — Scheme Settlement (nostro-at-scheme), per currency.
    private val SCHEME_SETTLEMENT_GL = mapOf(
        "CZK" to UUID.fromString("a0000000-0000-0000-0000-000000001110"),
        "EUR" to UUID.fromString("a0000000-0000-0000-0000-000000001111"),
        "USD" to UUID.fromString("a0000000-0000-0000-0000-000000001112"),
        "GBP" to UUID.fromString("a0000000-0000-0000-0000-000000001113"),
    )

    /** The technical actor stamped on every clearing-posted journal. */
    private val SYSTEM_ACTOR: UUID = UUID.fromString("a0000000-0000-0000-0000-00000000c1e4")

    fun build(posting: NetSettlementPosting): PostJournalRequest {
        val cashClearing = CASH_CLEARING_GL[posting.currency]
        val schemeSettlement = SCHEME_SETTLEMENT_GL[posting.currency]
        require(cashClearing != null && schemeSettlement != null) {
            "no settlement GL accounts seeded for currency ${posting.currency} " +
                "(ledger migrations V3/V14/V26 cover CZK/EUR/USD/GBP)"
        }
        val amount = posting.settlementAmount
        val date = posting.valueDate.toString()
        return PostJournalRequest(
            idempotencyKey = posting.idempotencyKey,
            transactionId = posting.batchId,
            entryDate = date,
            valueDate = date,
            description = "net settlement ${posting.batchReference} cycle ${posting.cycleId}",
            lines = listOf(
                JournalLineRequest(
                    glAccountId = cashClearing,
                    side = "DEBIT",
                    amount = amount,
                    currencyCode = posting.currency,
                    fxRate = null,
                    baseAmount = amount,
                    baseCurrencyCode = posting.currency,
                ),
                JournalLineRequest(
                    glAccountId = schemeSettlement,
                    side = "CREDIT",
                    amount = amount,
                    currencyCode = posting.currency,
                    fxRate = null,
                    baseAmount = amount,
                    baseCurrencyCode = posting.currency,
                ),
            ),
            createdBy = SYSTEM_ACTOR,
        )
    }

    /** Test and audit seam: the GL pair a currency's leg posts between. */
    fun glPairFor(currency: String): Pair<UUID?, UUID?> = CASH_CLEARING_GL[currency] to SCHEME_SETTLEMENT_GL[currency]
}
