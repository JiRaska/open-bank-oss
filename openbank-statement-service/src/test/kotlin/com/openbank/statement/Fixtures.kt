// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement

import com.openbank.statement.domain.model.BalanceAnchor
import com.openbank.statement.domain.model.CreditDebit
import com.openbank.statement.domain.model.StatementEntry
import com.openbank.statement.domain.model.StatementModel
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

object Fixtures {

    val ACCOUNT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    val CLOSED_AT: Instant = Instant.parse("2026-02-01T02:30:00Z")

    fun entry(
        ref: String = "TX-1",
        amount: String = "100.00",
        cd: CreditDebit = CreditDebit.CRDT,
        booking: String = "2026-01-15",
        value: String = "2026-01-16",
        currency: String = "CZK",
        description: String = "Salary",
    ) = StatementEntry(
        entryRef = ref,
        amount = BigDecimal(amount),
        currency = currency,
        creditDebit = cd,
        bookingDate = LocalDate.parse(booking),
        valueDate = LocalDate.parse(value),
        description = description,
    )

    fun model(
        currency: String = "CZK",
        opening: String = "1000.00",
        closing: String = "1075.00",
        entries: List<StatementEntry> = listOf(
            entry(ref = "TX-1", amount = "100.00", cd = CreditDebit.CRDT, currency = currency),
            entry(ref = "TX-2", amount = "25.00", cd = CreditDebit.DBIT, currency = currency, description = "Fee"),
        ),
        legalSeq: Long = 7,
        electronicSeq: Long = 7,
    ) = StatementModel(
        accountId = ACCOUNT_ID,
        iban = "CZ6508000000192000145399",
        currency = currency,
        holderName = "Jan Novak",
        periodFrom = LocalDate.parse("2026-01-01"),
        periodTo = LocalDate.parse("2026-01-31"),
        openingBalance = BalanceAnchor(BigDecimal(opening), currency, LocalDate.parse("2026-01-01")),
        closingBalance = BalanceAnchor(BigDecimal(closing), currency, LocalDate.parse("2026-01-31")),
        entries = entries,
        legalSequenceNumber = legalSeq,
        electronicSequenceNumber = electronicSeq,
        closedAt = CLOSED_AT,
    )
}
