// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hibernate.reactive.mutiny.Mutiny
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class PaymentBookingProjectionConsumerTest {
    private val meters = SimpleMeterRegistry()
    private val consumer = PaymentBookingProjectionConsumer(
        mockk<Mutiny.SessionFactory>(),
        jacksonObjectMapper(),
        Clock.fixed(Instant.parse("2026-09-13T10:00:00Z"), ZoneOffset.UTC),
        meters,
        "openbank-cz",
        1,
        500,
        true,
    )

    @Test
    fun `forged transaction source fails before persistence`() {
        val payload = transactionEvent(source = "forged-service")

        assertThatThrownBy { runBlocking { consumer.consumeTransaction(payload) } }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unexpected transaction event source")
        assertThat(failed("transaction")).isEqualTo(1.0)
    }

    @Test
    fun `strict ledger event requires an explicit aggregate version`() {
        val payload = ledgerEvent().replace("\"version\":0,", "")

        assertThatThrownBy { runBlocking { consumer.consumeLedger(payload) } }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("no strict aggregate version")
        assertThat(failed("ledger")).isEqualTo(1.0)
    }

    @Test
    fun `unrelated shared-topic events are ignored without persistence`(): Unit = runBlocking {
        consumer.consumeLedger("""{"eventType":"JournalReversed"}""")

        assertThat(meters.counter(EVENTS, "stream", "ledger", "outcome", "ignored").count()).isEqualTo(1.0)
    }

    private fun failed(stream: String) = meters.counter(EVENTS, "stream", stream, "outcome", "failed").count()

    private fun transactionEvent(source: String): String =
        """{"eventType":"TransactionInitiated","sourceService":"$source",""" +
            """"aggregateId":"${UUID.randomUUID()}","version":0,""" +
            """"originatingPaymentId":"${UUID.randomUUID()}","occurredAt":"2026-09-13T09:59:00Z"}"""

    private fun ledgerEvent(): String = """{"eventType":"JournalPosted","sourceService":"ledger-service",""" +
        """"aggregateId":"${UUID.randomUUID()}","version":0,"transactionId":"${UUID.randomUUID()}",""" +
        """"entryDate":"2026-09-13","occurredAt":"2026-09-13T09:59:00Z"}"""

    private companion object {
        const val EVENTS = "openbank_context_projection_events_total"
    }
}
