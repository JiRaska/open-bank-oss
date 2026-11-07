// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing

import com.openbank.billing.domain.FeeJournalCommand
import com.openbank.billing.domain.FeeReversalCommand
import com.openbank.billing.infrastructure.adapter.LedgerPostingAdapter
import com.openbank.billing.infrastructure.client.BillingLedgerConfig
import com.openbank.billing.infrastructure.client.LedgerJournalEntryResponse
import com.openbank.billing.infrastructure.client.LedgerPostJournalRequest
import com.openbank.billing.infrastructure.client.LedgerRestClient
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The REST ledger adapter must hand the ledger client a balanced journal built from the
 * configured GL chart, dated by the injected clock, and surface (not swallow) a ledger failure
 * so the outbox dispatcher's own `markFailed` retry loop sees it. Mirrors
 * `openbank-lending-service`'s `RestLedgerPostingAdapterTest`.
 */
class LedgerPostingAdapterTest {

    private val ledgerClient = mockk<LedgerRestClient>()
    private val clock = Clock.fixed(Instant.parse("2026-07-01T03:00:00Z"), ZoneOffset.UTC)

    private val config = object : BillingLedgerConfig {
        override fun systemActorId(): UUID = UUID.fromString("00000000-0000-0000-0000-0000000000bb")
    }

    private val adapter = LedgerPostingAdapter(ledgerClient, config, clock, postByClass = true)

    private fun command(idempotencyKey: String = "fee-2026-07-acc-1-f1-CZK") = FeeJournalCommand(
        idempotencyKey = idempotencyKey,
        cycleId = "2026-07",
        accountId = "acc-1",
        feeId = "f1",
        amount = BigDecimal("150.00"),
        currency = "CZK",
        description = "Fee charge: Maintenance",
        accountingClass = "CUSTOMER_DEPOSIT_DEMAND",
    )

    @Test
    fun `posts a clock-dated balanced journal whose legs name accounting classes and returns the journal id`() {
        val requestSlot: CapturingSlot<LedgerPostJournalRequest> = slot()
        val journalId = UUID.randomUUID()
        every { ledgerClient.postJournal(capture(requestSlot)) } returns Uni.createFrom().item(
            LedgerJournalEntryResponse(id = journalId, transactionId = UUID.randomUUID(), status = "POSTED"),
        )

        val result = runBlocking { adapter.post(command()) }

        assertThat(result).isEqualTo(journalId)
        val request = requestSlot.captured
        assertThat(request.idempotencyKey).isEqualTo("fee-2026-07-acc-1-f1-CZK")
        assertThat(request.entryDate).isEqualTo("2026-07-01")
        assertThat(request.valueDate).isEqualTo("2026-07-01")
        assertThat(request.createdBy).isEqualTo(config.systemActorId())
        assertThat(request.lines).hasSize(2)
        verify(exactly = 1) { ledgerClient.postJournal(any()) }
    }

    @Test
    fun `a ledger failure propagates instead of being swallowed`() {
        every { ledgerClient.postJournal(any()) } returns Uni.createFrom().failure(IllegalStateException("ledger down"))

        assertThatThrownBy { runBlocking { adapter.post(command()) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("ledger down")
    }

    private fun reversalCommand(idempotencyKey: String = "fee-reversal-2026-07-acc-1-f1-CZK") = FeeReversalCommand(
        idempotencyKey = idempotencyKey,
        originalIdempotencyKey = "fee-2026-07-acc-1-f1-CZK",
        cycleId = "2026-07",
        accountId = "acc-1",
        feeId = "f1",
        amount = BigDecimal("150.00"),
        currency = "CZK",
        reason = "waiver bug",
        accountingClass = "CUSTOMER_DEPOSIT_DEMAND",
    )

    @Test
    fun `posts a compensating journal via the same POST journals endpoint and returns the reversal journal id`() {
        val requestSlot: CapturingSlot<LedgerPostJournalRequest> = slot()
        val reversalJournalId = UUID.randomUUID()
        every { ledgerClient.postJournal(capture(requestSlot)) } returns Uni.createFrom().item(
            LedgerJournalEntryResponse(id = reversalJournalId, transactionId = UUID.randomUUID(), status = "POSTED"),
        )

        val result = runBlocking { adapter.postReversal(reversalCommand()) }

        assertThat(result).isEqualTo(reversalJournalId)
        val request = requestSlot.captured
        assertThat(request.idempotencyKey).isEqualTo("fee-reversal-2026-07-acc-1-f1-CZK")
        assertThat(request.entryDate).isEqualTo("2026-07-01")
        assertThat(request.createdBy).isEqualTo(config.systemActorId())
        assertThat(request.lines).hasSize(2)
        verify(exactly = 1) { ledgerClient.postJournal(any()) }
    }

    @Test
    fun `a reversal ledger failure propagates instead of being swallowed`() {
        every { ledgerClient.postJournal(any()) } returns Uni.createFrom().failure(IllegalStateException("ledger down"))

        assertThatThrownBy { runBlocking { adapter.postReversal(reversalCommand()) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("ledger down")
    }

    private fun capture(postByClass: Boolean, block: suspend (LedgerPostingAdapter) -> Unit): LedgerPostJournalRequest {
        val requestSlot: CapturingSlot<LedgerPostJournalRequest> = slot()
        every { ledgerClient.postJournal(capture(requestSlot)) } returns Uni.createFrom().item(
            LedgerJournalEntryResponse(UUID.randomUUID(), UUID.randomUUID(), "POSTED"),
        )
        runBlocking { block(LedgerPostingAdapter(ledgerClient, config, clock, postByClass)) }
        return requestSlot.captured
    }

    @Test
    fun `with the switch off a CZK fee's legs are the explicit accounts the ledger has always accepted`() {
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val charge = capture(false) { it.post(command().copy(currency = "CZK")) }
        val reversal = capture(false) { it.postReversal(reversalCommand().copy(currency = "CZK")) }
        for ((name, request) in listOf("charge" to charge, "reversal" to reversal)) {
            val legs = mapper.readTree(mapper.writeValueAsString(request))["lines"]
            assertThat(legs[0]["glAccountId"].asText()).describedAs("$name CZK customer leg")
                .isEqualTo("a0000000-0000-0000-0000-000000000002")
            assertThat(legs[1]["glAccountId"].asText()).describedAs("$name CZK income leg")
                .isEqualTo("a0000000-0000-0000-0000-000000004003")
            legs.forEach {
                // exactly the payload an older ledger knows: no class fields at all
                assertThat(it.has("accountingClass")).isFalse()
                assertThat(it.has("accountingRole")).isFalse()
            }
        }
        assertThat(charge.lines.map { it.side }).containsExactly("DEBIT", "CREDIT")
        assertThat(reversal.lines.map { it.side }).containsExactly("CREDIT", "DEBIT")
    }

    @Test
    fun `with the switch off a non-CZK fee still posts by class - the CZK literals were always refused for it`() {
        // #13226: 2100/4003 are CZK accounts; an EUR leg on them was a 422 that wedged the outbox row.
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val charge = capture(false) { it.post(command().copy(currency = "EUR")) }
        val legs = mapper.readTree(mapper.writeValueAsString(charge))["lines"]
        assertThat(legs[0]["accountingClass"].asText()).isEqualTo("CUSTOMER_DEPOSIT_DEMAND")
        assertThat(legs[1]["accountingClass"].asText()).isEqualTo("FEE_INCOME_ACCOUNT")
        legs.forEach { assertThat(it.has("glAccountId")).isFalse() }
    }

    @Test
    fun `with the switch on the legs name a class and role and no account`() {
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val request = capture(true) { it.post(command()) }
        val legs = mapper.readTree(mapper.writeValueAsString(request))["lines"]
        assertThat(legs[0]["accountingClass"].asText()).isEqualTo("CUSTOMER_DEPOSIT_DEMAND")
        assertThat(legs[0]["accountingRole"].asText()).isEqualTo("PRINCIPAL")
        assertThat(legs[1]["accountingClass"].asText()).isEqualTo("FEE_INCOME_ACCOUNT")
        legs.forEach { assertThat(it.has("glAccountId")).isFalse() }
    }
}
