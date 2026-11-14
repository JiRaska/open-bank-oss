// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.kafka

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.aml.domain.monitoring.MovementDirection
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class MonitoringEventParsingTest {
    private val reader = ObjectMapper().reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
    private val account = UUID.fromString("00000000-0000-4000-8000-0000000000a1")
    private val journal = UUID.fromString("00000000-0000-4000-8000-0000000000b1")
    private val transaction = UUID.fromString("00000000-0000-4000-8000-0000000000c1")

    private fun booked(delta: String, extra: String = "") =
        """{"eventId":"${UUID.randomUUID()}","eventType":"AccountBookedChanged","aggregateType":"Account",
           "aggregateId":"$account","version":3,"currency":"CZK","delta":$delta,"journalEntryId":"$journal",
           "transactionId":"$transaction","entryDate":"2026-03-01","occurredAt":"2026-03-01T10:05:00Z"$extra}"""

    @Test
    fun `a negative delta is money out and the amount keeps every decimal`() {
        val movement = LedgerBookedMovementConsumer.parse(reader.readTree(booked("-123456789012345.6789")))!!
        assertThat(movement.direction).isEqualTo(MovementDirection.OUT)
        assertThat(movement.amount).isEqualByComparingTo(BigDecimal("123456789012345.6789"))
        assertThat(movement.accountId).isEqualTo(account)
        assertThat(movement.journalEntryId).isEqualTo(journal)
        assertThat(movement.transactionId).isEqualTo(transaction)
        assertThat(movement.bookedAt).isEqualTo(Instant.parse("2026-03-01T10:05:00Z"))
    }

    @Test
    fun `a positive delta is money in`() {
        assertThat(LedgerBookedMovementConsumer.parse(reader.readTree(booked("40.00")))!!.direction)
            .isEqualTo(MovementDirection.IN)
    }

    @Test
    fun `other ledger event types on the shared topic are ignored, not rejected`() {
        assertThat(LedgerBookedMovementConsumer.parse(reader.readTree("""{"eventType":"JournalPosted"}"""))).isNull()
    }

    @Test
    fun `an incomplete or implausible booking is rejected so the dead-letter queue takes it`() {
        listOf(
            """{"eventType":"AccountBookedChanged","aggregateId":"$account"}""",
            booked("0"),
            booked("1.00001"),
            booked("-40.00").replace("CZK", "czk"),
            booked("-40.00").replace("2026-03-01T10:05:00Z", "yesterday"),
            "[]",
        ).forEach { payload ->
            assertThatThrownBy { LedgerBookedMovementConsumer.parse(reader.readTree(payload)) }
                .describedAs(payload).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `only TransactionInitiated yields a rail, and a missing rail is the producer's UNKNOWN`() {
        val initiated = """{"eventType":"TransactionInitiated","aggregateId":"$transaction","rail":"DOMESTIC"}"""
        assertThat(TransactionOriginConsumer.parse(reader.readTree(initiated))).isEqualTo(transaction to "DOMESTIC")
        val noRail = """{"eventType":"TransactionInitiated","aggregateId":"$transaction"}"""
        assertThat(TransactionOriginConsumer.parse(reader.readTree(noRail))).isEqualTo(transaction to "UNKNOWN")
        val completed = """{"eventType":"TransactionCompleted","aggregateId":"$transaction"}"""
        assertThat(TransactionOriginConsumer.parse(reader.readTree(completed))).isNull()
        assertThatThrownBy {
            TransactionOriginConsumer.parse(
                reader.readTree("""{"eventType":"TransactionInitiated","aggregateId":"x"}"""),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            TransactionOriginConsumer.parse(reader.readTree(initiated.replace("DOMESTIC", "not a rail")))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
