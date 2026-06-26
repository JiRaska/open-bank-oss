// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.balance.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.balance.application.port.`in`.AccountBookedChange
import com.openbank.balance.application.port.`in`.LedgerProjectionUseCase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class LedgerProjectionConsumerTest {

    private val mapper = ObjectMapper()

    private fun bookedChangedJson(
        accountId: UUID = UUID.randomUUID(),
        journalEntryId: UUID = UUID.randomUUID(),
        transactionId: UUID = UUID.randomUUID(),
        delta: String = "100.0000",
    ): String = mapper.writeValueAsString(
        mapOf(
            "eventType" to "AccountBookedChanged",
            "aggregateType" to "Account",
            "aggregateId" to accountId.toString(),
            "version" to 1,
            "currency" to "CZK",
            "delta" to delta,
            "journalEntryId" to journalEntryId.toString(),
            "transactionId" to transactionId.toString(),
            "entryDate" to "2026-01-15",
        ),
    )

    @Test
    fun `does nothing when the projection flag is off`(): Unit = runBlocking {
        val projection = RecordingProjection()
        val consumer = LedgerProjectionConsumer(projection, mapper, projectionEnabled = false)

        consumer.consume(bookedChangedJson())

        assertTrue(projection.applied.isEmpty())
    }

    @Test
    fun `ignores non-AccountBookedChanged events when enabled`(): Unit = runBlocking {
        val projection = RecordingProjection()
        val consumer = LedgerProjectionConsumer(projection, mapper, projectionEnabled = true)

        val journalPosted = mapper.writeValueAsString(
            mapOf("eventType" to "JournalPosted", "aggregateId" to UUID.randomUUID().toString()),
        )
        consumer.consume(journalPosted)

        assertTrue(projection.applied.isEmpty())
    }

    @Test
    fun `projects an AccountBookedChanged event when enabled`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val journalEntryId = UUID.randomUUID()
        val transactionId = UUID.randomUUID()
        val projection = RecordingProjection()
        val consumer = LedgerProjectionConsumer(projection, mapper, projectionEnabled = true)

        consumer.consume(bookedChangedJson(accountId, journalEntryId, transactionId, delta = "-40.0000"))

        val change = projection.applied.single()
        assertEquals(accountId, change.accountId)
        assertEquals(journalEntryId, change.journalEntryId)
        assertEquals(transactionId, change.transactionId)
        assertEquals(0, change.delta.compareTo(BigDecimal("-40.00")))
        assertEquals("CZK", change.currency)
    }

    @Test
    fun `swallows an unparseable payload without applying`(): Unit = runBlocking {
        val projection = RecordingProjection()
        val consumer = LedgerProjectionConsumer(projection, mapper, projectionEnabled = true)

        consumer.consume("{ not json")

        assertTrue(projection.applied.isEmpty())
    }

    private class RecordingProjection : LedgerProjectionUseCase {
        val applied = mutableListOf<AccountBookedChange>()
        override suspend fun apply(change: AccountBookedChange) {
            applied += change
        }
    }
}
