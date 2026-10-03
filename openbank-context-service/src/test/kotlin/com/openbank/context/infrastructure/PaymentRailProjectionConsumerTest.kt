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

class PaymentRailProjectionConsumerTest {
    private val meters = SimpleMeterRegistry()
    private val consumer = PaymentRailProjectionConsumer(
        mockk<Mutiny.SessionFactory>(),
        jacksonObjectMapper(),
        Clock.fixed(Instant.parse("2026-09-14T10:00:00Z"), ZoneOffset.UTC),
        meters,
        "openbank-cz",
        1,
        500,
        true,
    )

    @Test
    fun `forged clearing source fails before persistence`() {
        val payload = clearingEvent(source = "forged-service")

        assertThatThrownBy { runBlocking { consumer.consumeClearing(payload) } }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unexpected clearing event source")
        assertThat(failed("clearing")).isEqualTo(1.0)
    }

    @Test
    fun `strict SEPA return requires an explicit aggregate version`() {
        val payload = sepaReturnEvent().replace("\"version\":3,", "")

        assertThatThrownBy { runBlocking { consumer.consumeSepaReturn(payload) } }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("no strict aggregate version")
        assertThat(failed("sepa-return")).isEqualTo(1.0)
    }

    @Test
    fun `zero clearing revision cannot create evidence`() {
        val payload = clearingEvent(source = "clearing-service").replace("\"version\":2", "\"version\":0")

        assertThatThrownBy { runBlocking { consumer.consumeClearing(payload) } }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("misses settled item identity")
        assertThat(failed("clearing")).isEqualTo(1.0)
    }

    @Test
    fun `SEPA return cannot claim a reversal identity when reversal did not happen`() {
        val payload = sepaReturnEvent().replace("\"reversalPerformed\":true", "\"reversalPerformed\":false")

        assertThatThrownBy { runBlocking { consumer.consumeSepaReturn(payload) } }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unsupported reversal identity")
        assertThat(failed("sepa-return")).isEqualTo(1.0)
    }

    @Test
    fun `unrelated shared clearing events are ignored without persistence`(): Unit = runBlocking {
        consumer.consumeClearing("""{"eventType":"openbank.clearing.batch.settled"}""")

        assertThat(meters.counter(EVENTS, "stream", "clearing", "outcome", "ignored").count()).isEqualTo(1.0)
    }

    private fun failed(stream: String) = meters.counter(EVENTS, "stream", stream, "outcome", "failed").count()

    private fun clearingEvent(source: String): String =
        """{"eventType":"openbank.clearing.item.cleared","sourceService":"$source",""" +
            """"itemId":"${UUID.randomUUID()}","batchId":"${UUID.randomUUID()}",""" +
            """"paymentId":"${UUID.randomUUID()}","version":2,"status":"SETTLED",""" +
            """"occurredAt":"2026-09-14T09:59:00Z"}"""

    private fun sepaReturnEvent(): String = """{"eventType":"sepa.payment.returned","sourceService":"sepa-payment",""" +
        """"paymentId":"${UUID.randomUUID()}","version":3,"reversalPerformed":true,""" +
        """"reversalTransactionId":"${UUID.randomUUID()}",""" +
        """"occurredAt":"2026-09-14T09:59:00Z"}"""

    private companion object {
        const val EVENTS = "openbank_context_projection_events_total"
    }
}
