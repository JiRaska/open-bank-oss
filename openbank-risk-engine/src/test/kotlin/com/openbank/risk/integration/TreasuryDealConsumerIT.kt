// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.integration

import com.openbank.risk.application.port.out.TreasuryDealBook
import com.openbank.risk.it.PostgresTestResource
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.internals.RecordHeaders
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Message
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/**
 * ADR-0315 D6: treasury's deal events through the in-memory connector into a real Postgres. The
 * record carries its `ce-type` header exactly as the outbox publishes it, so the header read is
 * what is tested, not a payload guess. The book is monotonic: a settled deal stays settled when
 * its (late) booked event arrives, and only the booked event's rate is filled in.
 */
@QuarkusTest
@QuarkusTestResource(RiskSnapshotApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class TreasuryDealConsumerIT {

    @Inject
    @jakarta.enterprise.inject.Any
    lateinit var connector: InMemoryConnector

    @Inject
    lateinit var book: TreasuryDealBook

    @Inject
    lateinit var registry: MeterRegistry

    private val deal = UUID.randomUUID()
    private val valueDate = LocalDate.parse("2026-09-25")
    private val maturity = LocalDate.parse("2026-09-28")

    private fun outcome(name: String): Double =
        registry.find("openbank_risk_treasury_deal_events").tag("outcome", name).counter()?.count() ?: 0.0

    private fun payload(extra: String = "") = """
        {"dealId":"$deal","product":"CNB_DEPOSIT_FACILITY","counterpartyId":"CNB","currency":"CZK",
         "principal":10000000.00,"valueDate":"$valueDate","maturityDate":"$maturity"$extra,
         "occurredAt":"2026-09-25T07:40:00Z","sourceService":"treasury-service"}
    """.trimIndent()

    private fun record(type: String?, body: String): Message<String> {
        val headers = RecordHeaders()
        type?.let { headers.add("ce-type", it.toByteArray()) }
        val consumerRecord = ConsumerRecord(
            "openbank.treasury.deal.events", 0, 0L, ConsumerRecord.NO_TIMESTAMP,
            org.apache.kafka.common.record.TimestampType.NO_TIMESTAMP_TYPE, 0, 0,
            deal.toString(), body, headers, java.util.Optional.empty(),
        )
        return Message.of(body).addMetadata(IncomingKafkaRecordMetadata(consumerRecord, "treasury-deal-in"))
    }

    @Test
    fun `settled before booked still ends settled with the booked rate, and a replay changes nothing`() {
        // Typed Any: the in-memory source forwards a Message as-is, so the Kafka metadata travels with it.
        val source = connector.source<Any>("treasury-deal-in")
        source.send(record("treasury.deal.settled.v1", payload(""","ledgerJournalId":"${UUID.randomUUID()}"""")))
        awaitState("SETTLED")
        // Late booked: must NOT move the state back, but must fill the rate the settled event lacked.
        source.send(record("treasury.deal.booked.v1", payload(""","rate":2.50,"createdBy":"d","approvedBy":"a"""")))
        awaitRate()
        // Malformed ones in between are acked and do not stop what follows.
        source.send(record("treasury.deal.unknown.v1", payload()))
        source.send(record(null, payload()))
        source.send(record("treasury.deal.settled.v1", """{"dealId":"$deal"}"""))
        source.send(record("treasury.deal.settled.v1", payload()))
        // The three malformed sends above are processed asynchronously by the consumer; without a
        // wait here, the counter assertions below race the consumer and can read fewer than 3.
        awaitOutcomeAtLeast("malformed", 3.0)

        val onBook = runBlocking { book.dealsOnBook(LocalDate.parse("2026-09-26")) }.single { it.dealId == deal }
        assertThat(onBook.state).isEqualTo("SETTLED")
        assertThat(onBook.rate).isEqualByComparingTo("2.50")
        assertThat(outcome("malformed")).isGreaterThanOrEqualTo(3.0)
        assertThat(outcome("write_error")).isEqualTo(0.0)
        assertThat(outcome("unchanged")).isGreaterThanOrEqualTo(1.0)

        source.send(record("treasury.deal.matured.v1", payload(""","interest":694.44""")))
        awaitState("MATURED")
        assertThat(runBlocking { book.dealsOnBook(LocalDate.parse("2026-09-26")) }.map { it.dealId }).contains(deal)
        assertThat(runBlocking { book.dealsOnBook(maturity) }.map { it.dealId }).doesNotContain(deal)
    }

    @org.junit.jupiter.api.AfterEach
    fun cleanup() = TestDb.execute("DELETE FROM treasury_deal WHERE deal_id = '$deal'")

    private fun awaitState(expected: String) = await { current()?.state == expected }

    private fun awaitRate() = await { current()?.rate != null }

    private fun awaitOutcomeAtLeast(name: String, min: Double) = await { outcome(name) >= min }

    private fun current() =
        runBlocking { book.dealsOnBook(LocalDate.parse("2026-09-26")) }.singleOrNull { it.dealId == deal }
            ?: runBlocking { book.dealsOnBook(valueDate) }.singleOrNull { it.dealId == deal }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TIMEOUT_NANOS
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
        assertThat(condition()).isTrue()
    }

    private companion object {
        const val TIMEOUT_NANOS = 10_000_000_000L
        const val POLL_MS = 100L
    }
}
