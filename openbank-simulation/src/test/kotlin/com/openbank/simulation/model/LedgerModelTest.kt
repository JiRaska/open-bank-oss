// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.model

import com.openbank.balance.domain.model.Balance
import com.openbank.ledger.domain.model.JournalEntry
import com.openbank.ledger.domain.model.JournalLine
import com.openbank.ledger.domain.model.JournalSide
import com.openbank.ledger.domain.model.JournalStatus
import com.openbank.ledger.domain.model.LedgerValidationException
import com.openbank.libs.domain.money.Money
import com.openbank.simulation.adapters.AuditLog
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Exercises the harness wiring against the REAL openbank-ledger-service JournalEntry aggregate. */
class LedgerModelTest {

    private val gl = UUID(0L, 1L)

    private fun czk(amount: String) = Money.of(BigDecimal(amount), "CZK")

    private fun transfer(
        payer: UUID,
        payee: UUID,
        debit: String,
        credit: String = debit,
        status: JournalStatus = JournalStatus.POSTED,
    ): JournalEntry {
        val jid = UUID.randomUUID()
        return JournalEntry(
            id = jid,
            entryNumber = null,
            transactionId = UUID.randomUUID(),
            entryDate = LocalDate.of(2026, 1, 1),
            valueDate = LocalDate.of(2026, 1, 1),
            description = null,
            status = status,
            lines = listOf(
                JournalLine(
                    UUID.randomUUID(), jid, gl, JournalSide.DEBIT,
                    czk(
                        debit,
                    ),
                    null, czk(debit), 1, subAccountId = payer,
                ),
                JournalLine(
                    UUID.randomUUID(), jid, gl, JournalSide.CREDIT,
                    czk(
                        credit,
                    ),
                    null, czk(credit), 2, subAccountId = payee,
                ),
            ),
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            createdBy = UUID(0L, 2L),
            version = 0L,
        )
    }

    @Test
    fun `a balanced transfer conserves money and projects credit-positive deltas`() {
        val payer = UUID.randomUUID()
        val payee = UUID.randomUUID()
        val ledger = LedgerState()
        ledger.post("k1", transfer(payer, payee, "100.00"))

        assertThat(ledger.lineNetByCurrency()["CZK"]).isEqualByComparingTo(BigDecimal.ZERO)
        val deltas = ledger.netDeltas()
        assertThat(deltas[AccountCurrency(payer, "CZK")]).isEqualByComparingTo(BigDecimal("-100.00"))
        assertThat(deltas[AccountCurrency(payee, "CZK")]).isEqualByComparingTo(BigDecimal("100.00"))
    }

    @Test
    fun `posting the same idempotency key twice does not double-post`() {
        val ledger = LedgerState()
        val entry = transfer(UUID.randomUUID(), UUID.randomUUID(), "50.00")
        ledger.post("same-key", entry)
        ledger.post("same-key", transfer(UUID.randomUUID(), UUID.randomUUID(), "50.00"))
        assertThat(ledger.postedCount()).isEqualTo(1)
    }

    @Test
    fun `the real aggregate rejects an unbalanced journal at construction`() {
        assertThatThrownBy {
            transfer(
                UUID.randomUUID(),
                UUID.randomUUID(),
                debit = "100.00",
                credit = "50.00",
                status = JournalStatus.PENDING,
            )
        }.isInstanceOf(LedgerValidationException::class.java)
    }

    @Test
    fun `a reservation respects the overdraft floor`() {
        val accountId = UUID.randomUUID()
        val balance = Balance(
            id = UUID(0L, 0L),
            accountId = accountId,
            currency = "CZK",
            bookedAmount = BigDecimal("100.00"),
            availableAmount = BigDecimal("100.00"),
            reservedAmount = BigDecimal.ZERO,
            pendingAmount = BigDecimal.ZERO,
            updatedAt = OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC),
            version = 0L,
        )
        assertThatThrownBy { balance.withReservation(BigDecimal("150.00")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the audit chain verifies for an append-only log`() {
        val log = AuditLog()
        repeat(5) { log.append("event-$it") }
        assertThat(log.verifyChain()).isTrue()
        assertThat(log.size()).isEqualTo(5)
    }
}
