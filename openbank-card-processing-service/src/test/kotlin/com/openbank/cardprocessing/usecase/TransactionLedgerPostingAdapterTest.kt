// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.usecase

import com.openbank.cardprocessing.application.port.out.PostingOutcome
import com.openbank.cardprocessing.domain.model.AuthorizationStatus
import com.openbank.cardprocessing.domain.model.CardAuthorization
import com.openbank.cardprocessing.domain.model.PresentmentChannel
import com.openbank.cardprocessing.infrastructure.client.CardClearingLedgerKey
import com.openbank.cardprocessing.infrastructure.client.InitiateTransactionRequest
import com.openbank.cardprocessing.infrastructure.client.TransactionLedgerPostingAdapter
import com.openbank.cardprocessing.infrastructure.client.TransactionResponse
import com.openbank.cardprocessing.infrastructure.client.TransactionServiceClient
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The boundary where minor units become money in the books.
 *
 * Three properties are pinned here because each fails silently in production: the scale conversion
 * (a hardcoded 100 posts a hundredth of a yen), the disabled path (a skip counted as a success is
 * how undelivered pushes were reported as delivered, #4348), and the failure path (an exception
 * escaping here would undo a clearing the acquirer already asserted).
 */
class TransactionLedgerPostingAdapterTest {

    private val now = Instant.parse("2026-09-05T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val client = mockk<TransactionServiceClient>()

    private fun authorization(currency: String, amount: Long = 12_345) = CardAuthorization(
        id = UUID.randomUUID(),
        cardId = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        partyId = UUID.randomUUID(),
        amountMinorUnits = amount,
        currencyCode = currency,
        channel = PresentmentChannel.ONLINE,
        mcc = "5411",
        merchantName = "Potraviny",
        merchantCountry = "CZ",
        status = AuthorizationStatus.APPROVED,
        category = "GROCERIES",
        declineReason = null,
        clearedAmountMinorUnits = 0,
        networkReference = "acq-1",
        authorizedAt = now,
        expiresAt = now.plusSeconds(3600),
        updatedAt = now,
    )

    @Test
    fun `a two-decimal currency is posted with two decimals`(): Unit = runBlocking {
        val sent = slot<InitiateTransactionRequest>()
        coEvery { client.initiate(capture(sent)) } returns TransactionResponse(UUID.randomUUID(), "COMPLETED")
        val adapter = TransactionLedgerPostingAdapter(client, clock, postingEnabled = true)

        val authorization = authorization("CZK")
        val result = adapter.postClearedSpend(authorization, 12_345, "clr-1")

        assertThat(result.outcome).isEqualTo(PostingOutcome.POSTED)
        assertThat(sent.captured.amount).isEqualByComparingTo(BigDecimal("123.45"))
        assertThat(sent.captured.rail).isEqualTo("CARD")
        assertThat(sent.captured.sourceAccountId).isNotNull()
        // Derived from the clearing, so a retried presentment presents the same key and
        // transaction-service dedupes it instead of debiting the customer twice.
        // Scoped by the authorisation: a clearing key is only unique per authorisation.
        assertThat(sent.captured.idempotencyKey).isEqualTo("card-clearing:${authorization.id}:clr-1")
        Unit
    }

    @Test
    fun `the same clearing key on two authorisations gives two ledger keys`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()

        // The unscoped key made transaction-service dedupe the second authorisation's posting away.
        assertThat(CardClearingLedgerKey.of(a, "clr-1"))
            .isNotEqualTo(CardClearingLedgerKey.of(b, "clr-1"))
    }

    @Test
    fun `a long clearing key is digested to fit the ledger column, deterministically`() {
        val id = UUID.randomUUID()
        val longKey = "k".repeat(128)

        val key = CardClearingLedgerKey.of(id, longKey)

        assertThat(key.length).isLessThanOrEqualTo(CardClearingLedgerKey.MAX_LENGTH)
        assertThat(key).startsWith("card-clearing:$id:sha256-")
        assertThat(CardClearingLedgerKey.of(id, longKey)).isEqualTo(key)
        assertThat(CardClearingLedgerKey.of(id, longKey + "x")).isNotEqualTo(key)
    }

    @Test
    fun `a zero-decimal currency is posted whole, not divided by a hundred`(): Unit = runBlocking {
        val sent = slot<InitiateTransactionRequest>()
        coEvery { client.initiate(capture(sent)) } returns TransactionResponse(UUID.randomUUID(), "COMPLETED")
        val adapter = TransactionLedgerPostingAdapter(client, clock, postingEnabled = true)

        adapter.postClearedSpend(authorization("JPY"), 12_345, "clr-2")

        // Why the conversion asks the Currency for its fraction digits: JPY has none, and a
        // hardcoded 100 would post 123.45 yen for a 12,345 yen purchase — a hundredth of the money,
        // plausibly, with nothing anywhere disagreeing.
        assertThat(sent.captured.amount).isEqualByComparingTo(BigDecimal("12345"))
        Unit
    }

    @Test
    fun `a disabled adapter reports SKIPPED_DISABLED and calls nothing`(): Unit = runBlocking {
        val adapter = TransactionLedgerPostingAdapter(client, clock, postingEnabled = false)

        val result = adapter.postClearedSpend(authorization("CZK"), 1_000, "clr-3")

        assertThat(result.outcome).isEqualTo(PostingOutcome.SKIPPED_DISABLED)
        assertThat(result.transactionId).isNull()
        // The property that matters: a skip is its OWN outcome value, never a success. A boolean
        // here is how a switched-off adapter reads as a working one and no signal disagrees.
        assertThat(result.outcome).isNotEqualTo(PostingOutcome.POSTED)
        Unit
    }

    @Test
    fun `a failing transaction-service is FAILED, and the exception does not escape`(): Unit = runBlocking {
        coEvery { client.initiate(any()) } throws IllegalStateException("connection refused")
        val adapter = TransactionLedgerPostingAdapter(client, clock, postingEnabled = true)

        val result = adapter.postClearedSpend(authorization("CZK"), 1_000, "clr-4")

        // Escaping would undo a clearing the acquirer has already asserted, so the exception is
        // caught deliberately — and turned into a distinct outcome rather than swallowed.
        assertThat(result.outcome).isEqualTo(PostingOutcome.FAILED)
        assertThat(result.detail).contains("connection refused")
        Unit
    }
}
