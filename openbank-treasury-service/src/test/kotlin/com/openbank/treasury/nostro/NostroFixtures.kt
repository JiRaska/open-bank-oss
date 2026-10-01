// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.nostro

import com.openbank.treasury.domain.model.LedgerNostroLine
import com.openbank.treasury.domain.model.Side
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** Synthetic fixture data (#10896): the camt.053 file and the ledger lines that should match it. */
object NostroFixtures {
    const val IBAN = "CZ1299990000000000001001"
    val DATE: LocalDate = LocalDate.parse("2026-09-25")
    val INBOUND_TX: UUID = UUID.fromString("0b6f3c1e-8a2d-4c1b-9e47-3f5a6d7c8e91")

    fun xml(): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/camt053/nostro-czk-2026-09-25.xml")).readAllBytes()

    fun line(
        amount: String,
        side: Side,
        tx: UUID = UUID.randomUUID(),
        description: String? = null,
        date: LocalDate = DATE,
        currency: String = "CZK",
    ) = LedgerNostroLine(
        UUID.randomUUID(),
        UUID.randomUUID(),
        tx,
        date,
        side,
        BigDecimal(amount),
        currency,
        description,
    )

    /** The SWIFT MT940 twin of [xml] (#11107 follow-up): same account, balances and movements. */
    fun mt940(statementId: String = "SYNTH-940-0925"): ByteArray =
        String(requireNotNull(javaClass.getResourceAsStream("/mt940/nostro-czk-2026-09-25.txt")).readAllBytes())
            .replace("SYNTH-940-0925", statementId)
            .toByteArray()

    const val EUR_IBAN = "CZ8299990000000000001002"

    /** Two booking days (2026-09-24 and -25), EUR, DtTm on the closing balance and one entry. */
    fun eurXml(statementId: String = "SYNTH-STMT-20260925-EUR"): ByteArray =
        String(requireNotNull(javaClass.getResourceAsStream("/camt053/nostro-eur-2026-09-25.xml")).readAllBytes())
            .replace("SYNTH-STMT-20260925-EUR", statementId)
            .toByteArray()

    /** Ledger side of the EUR statement: both movements, native EUR amounts, one per day. */
    fun eurLedgerLines() = listOf(
        line(
            "10000.00",
            Side.DEBIT,
            description = "EUR MM maturity SYNTH-EUR-0001",
            date = DATE.minusDays(1),
            currency = "EUR",
        ),
        line("2500.00", Side.CREDIT, description = "EUR placement SYNTH-EUR-0002", currency = "EUR"),
    )

    /** Ledger side of the fixture day: two lines that agree with the statement, one fee the bank never saw. */
    fun ledgerLines(inboundAmount: String = "250000.00") = listOf(
        line(inboundAmount, Side.DEBIT, tx = INBOUND_TX, description = "treasury deal matured"),
        line("100000.00", Side.CREDIT, description = "MM placement settled SYNTH-SVCR-0002"),
        line("42.00", Side.CREDIT, description = "correspondent fee accrual"),
    )
}
