// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache-2.0 license.
package com.openbank.interest.infrastructure.client

import com.openbank.interest.application.port.out.CapitalizationPosting
import com.openbank.libs.domain.money.Money
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class CapitalizationReceiptTest {
    private val guard = mockk<LedgerCallGuard>()
    private val config = mockk<InterestLedgerConfig>()
    private val gl = mockk<InterestLedgerConfig.Gl>()
    private val accountId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val journalId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val posting = CapitalizationPosting(
        accountId,
        accountId.toString(),
        LocalDate.of(2026, 10, 1),
        Money(BigDecimal("10"), com.openbank.libs.domain.money.CurrencyCode.of("EUR")),
        Money(BigDecimal.ZERO, com.openbank.libs.domain.money.CurrencyCode.of("EUR")),
        Money(BigDecimal("10"), com.openbank.libs.domain.money.CurrencyCode.of("EUR")),
    )

    private fun adapter(): RestLedgerPostingAdapter {
        every { config.gl() } returns gl
        every { config.systemActorId() } returns accountId
        every { gl.interestExpenseEur() } returns accountId
        every { gl.withholdingTaxPayable() } returns accountId
        return RestLedgerPostingAdapter(guard, config)
    }

    @Test
    fun confirmedReceiptPreservesActualJournalWithoutSecondPost() {
        every { guard.postJournal(any()) } answers {
            val request = firstArg<PostJournalRequest>()
            Uni.createFrom().item(JournalResponse(journalId, request.transactionId, "POSTED"))
        }
        assertEquals(journalId, adapter().postWithReceipt(posting).await().indefinitely())
        verify(exactly = 1) { guard.postJournal(any()) }
    }

    @Test
    fun unrelatedReceiptCannotBindCapitalization() {
        every { guard.postJournal(any()) } returns
            Uni.createFrom().item(JournalResponse(journalId, accountId, "POSTED"))
        assertThrows(IllegalStateException::class.java) { adapter().postWithReceipt(posting).await().indefinitely() }
    }

    @Test
    fun reversedReceiptCannotPretendPosted() {
        every { guard.postJournal(any()) } answers {
            Uni.createFrom().item(JournalResponse(journalId, firstArg<PostJournalRequest>().transactionId, "REVERSED"))
        }
        assertThrows(IllegalStateException::class.java) { adapter().postWithReceipt(posting).await().indefinitely() }
    }
}
