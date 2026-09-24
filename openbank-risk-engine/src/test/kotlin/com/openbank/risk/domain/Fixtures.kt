// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain

import com.openbank.risk.domain.model.LedgerInputs
import com.openbank.risk.domain.model.SubLedgerBalance
import com.openbank.risk.domain.model.TrialBalanceLine
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

object Fixtures {
    val AS_OF: LocalDate = LocalDate.parse("2026-09-30")
    val ALICE: UUID = UUID.fromString("00000000-0000-7000-8000-000000000001")
    val BOB: UUID = UUID.fromString("00000000-0000-7000-8000-000000000002")

    /** Trial-balance line in the ledger convention: net = debit − credit. */
    fun tb(code: String, type: String, currency: String, debit: String, credit: String) = TrialBalanceLine(
        glAccountCode = code,
        glAccountType = type,
        currency = currency,
        totalDebit = BigDecimal(debit),
        totalCredit = BigDecimal(credit),
        net = BigDecimal(debit).subtract(BigDecimal(credit)),
    )

    fun sl(id: UUID, currency: String, debit: String, credit: String) =
        SubLedgerBalance(id, currency, BigDecimal(debit), BigDecimal(credit))

    /**
     * A balanced little bank: nostro 1001 holds 1500 CZK, customers Alice (1000) and Bob (500)
     * hold it on deposit control 2100. The two customer credits sum to the control account.
     */
    fun tiedOut(): LedgerInputs = LedgerInputs(
        asOf = AS_OF,
        trialBalance = listOf(
            tb("1001", "ASSET", "CZK", "1500.00", "0"),
            tb("2100", "LIABILITY", "CZK", "0", "1500.00"),
        ),
        subLedger = listOf(
            sl(ALICE, "CZK", "0", "1000.00"),
            sl(BOB, "CZK", "0", "500.00"),
        ),
    )
}
