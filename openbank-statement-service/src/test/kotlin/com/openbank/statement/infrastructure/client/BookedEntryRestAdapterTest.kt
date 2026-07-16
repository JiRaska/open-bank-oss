// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.infrastructure.client

import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Pagination behavior of [BookedEntryRestAdapter]. transaction-service caps `limit` at 200 and
 * defaults it to 50 — before the pagination loop a pocket with 51+ entries in a month got a silently
 * truncated statement that could never pass reconciliation. These tests pin: full-window paging,
 * upstream-order preservation, and the fail-closed hard cap.
 */
class BookedEntryRestAdapterTest {

    private val accountId: UUID = UUID.randomUUID()
    private val from: LocalDate = LocalDate.parse("2026-06-01")
    private val to: LocalDate = LocalDate.parse("2026-06-30")

    /** Serves a fixed dataset page-by-page according to limit/offset, recording every call. */
    private class PagingFakeClient(private val dataset: List<TransactionDto>) : TransactionRestClient {
        val calls = mutableListOf<Pair<Int, Int>>() // (limit, offset)

        override fun searchByAccount(
            accountId: UUID,
            dateFrom: String,
            dateTo: String,
            status: String,
            limit: Int,
            offset: Int,
        ): Uni<TransactionSearchResponse> {
            calls += limit to offset
            val page = dataset.drop(offset).take(limit)
            return Uni.createFrom().item(TransactionSearchResponse(page))
        }
    }

    private fun dto(i: Int, currency: String = "CZK") = TransactionDto(
        referenceNumber = "TX-%05d".format(i),
        amount = BigDecimal("10.00"),
        currencyCode = currency,
        sourceAccountId = accountId.toString(),
        bookingDate = "2026-06-15",
        status = "COMPLETED",
    )

    @Test
    fun `pages through the whole window and preserves upstream order`() {
        // 437 entries → pages of 200 + 200 + 37; the short last page terminates the loop.
        val client = PagingFakeClient((0 until 437).map { dto(it) })
        val adapter = BookedEntryRestAdapter(client)

        val entries = adapter.bookedEntries(accountId, "CZK", from, to)
            .subscribe().withSubscriber(UniAssertSubscriber.create()).awaitItem().item

        assertThat(entries).hasSize(437)
        assertThat(client.calls).containsExactly(200 to 0, 200 to 200, 200 to 400)
        // Concatenated pages must keep the upstream ordering (initiatedAt desc from the service).
        assertThat(entries.map { it.entryRef }).isEqualTo((0 until 437).map { "TX-%05d".format(it) })
    }

    @Test
    fun `an exact page-multiple window needs one extra empty page to terminate`() {
        val client = PagingFakeClient((0 until 200).map { dto(it) })
        val adapter = BookedEntryRestAdapter(client)

        val entries = adapter.bookedEntries(accountId, "CZK", from, to)
            .subscribe().withSubscriber(UniAssertSubscriber.create()).awaitItem().item

        assertThat(entries).hasSize(200)
        assertThat(client.calls).containsExactly(200 to 0, 200 to 200)
    }

    @Test
    fun `currency filter applies across all pages`() {
        // Alternate currencies so the filter has to work on every page, not just the first.
        val client = PagingFakeClient((0 until 437).map { dto(it, currency = if (it % 2 == 0) "CZK" else "EUR") })
        val adapter = BookedEntryRestAdapter(client)

        val entries = adapter.bookedEntries(accountId, "EUR", from, to)
            .subscribe().withSubscriber(UniAssertSubscriber.create()).awaitItem().item

        assertThat(entries).hasSize(218)
        assertThat(entries).allSatisfy { assertThat(it.currency).isEqualTo("EUR") }
    }

    @Test
    fun `a non-terminating upstream fails loudly instead of minting a truncated statement`() {
        // Pathological upstream: every page is full regardless of offset. The adapter must fail
        // closed at the hard cap rather than return a silently incomplete window.
        val fullPage = (0 until 200).map { dto(it) }
        val client = object : TransactionRestClient {
            var calls = 0
            override fun searchByAccount(
                accountId: UUID,
                dateFrom: String,
                dateTo: String,
                status: String,
                limit: Int,
                offset: Int,
            ): Uni<TransactionSearchResponse> {
                calls++
                return Uni.createFrom().item(TransactionSearchResponse(fullPage))
            }
        }
        val adapter = BookedEntryRestAdapter(client)

        val failure = adapter.bookedEntries(accountId, "CZK", from, to)
            .subscribe().withSubscriber(UniAssertSubscriber.create()).awaitFailure().failure

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure).hasMessageContaining("truncated")
        assertThat(client.calls).isEqualTo(100) // 100 pages × 200 = the 20_000-entry hard cap
    }
}
