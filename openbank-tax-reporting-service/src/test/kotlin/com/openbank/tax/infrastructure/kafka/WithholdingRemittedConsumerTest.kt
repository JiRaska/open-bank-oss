// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.messaging.EventRetry
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.tax.application.usecase.TaxFilingService
import com.openbank.tax.domain.model.ObservedRemittance
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The §38d filing consumer's failure contract (#5698/#5745).
 *
 * This consumer had no test at all, and the untested branch was the one that matters: a failed
 * `TaxFilingService.observe` was counted and acked. `assemble` totals only what was *observed*, so a
 * lost remittance never shows up as an error or a gap — it silently understates the tax withheld on
 * a return that then gets filed, and `auto.offset.reset: latest` rules out recovering it by replay.
 *
 * Each test below is written to discriminate, not to describe: run them against the pre-fix consumer
 * and the two behaviour tests go red while the malformed-payload test stays green on both sides.
 */
/** A named transient failure, so the tests below never `throw RuntimeException` (detekt). */
private class FilingStoreUnavailable(message: String) : RuntimeException(message)

class WithholdingRemittedConsumerTest {

    private val filings = mockk<TaxFilingService>()
    private val registry = SimpleMeterRegistry()
    private val clock = Clock.fixed(Instant.parse("2026-08-05T09:00:00Z"), ZoneOffset.UTC)
    private val consumer = WithholdingRemittedConsumer(filings, ObjectMapper(), registry, clock)

    private val remittanceId = UUID.randomUUID()

    private fun record(
        value: String,
        eventType: String? = "interest.withholding.remitted.v1",
    ): ConsumerRecord<String, String> {
        val r = ConsumerRecord("openbank.interest.accrual.event", 0, 0L, "key", value)
        if (eventType != null) {
            r.headers().add(
                RecordHeader(OutboxKafkaHeaders.HEADER_EVENT_TYPE, eventType.toByteArray(StandardCharsets.UTF_8)),
            )
        }
        return r
    }

    private fun payload(id: UUID = remittanceId) = """
        {"remittanceId":"$id","periodYear":2026,"periodMonth":7,"currency":"CZK",
         "totalTaxAmount":"1234.56","itemCount":9,"dueDate":"2026-08-20"}
    """.trimIndent()

    private fun outcome(value: String): Double =
        registry.counter("openbank.tax.withholding_events", "outcome", value).count()

    @Test
    fun `a remitted event is observed into the filing period`(): Unit = runBlocking {
        coEvery { filings.observe(any()) } returns true

        consumer.consume(record(payload()))

        coVerify(exactly = 1) {
            filings.observe(
                match<ObservedRemittance> {
                    it.remittanceId == remittanceId && it.totalTaxAmount.toPlainString() == "1234.56"
                },
            )
        }
        assertThat(outcome("recorded")).isEqualTo(1.0)
    }

    /**
     * The assertion that matters. A `filingRepository.openIfAbsent` / `remittanceRepository.record`
     * failure is a database being down, not a bad event — the remittance must reach the return once
     * it recovers, or the return understates the tax withheld.
     */
    @Test
    fun `a persistent write failure is RETHROWN after the bounded attempts`(): Unit = runBlocking {
        coEvery { filings.observe(any()) } throws FilingStoreUnavailable("tax-db down")

        assertThrows<FilingStoreUnavailable> { runBlocking { consumer.consume(record(payload())) } }

        // Bounded, so one outage cannot block the partition indefinitely.
        coVerify(exactly = EventRetry.DEFAULT_MAX_ATTEMPTS) { filings.observe(any()) }
        // Counted before the rethrow, so the `failed` population survives the connector's decision.
        assertThat(outcome("failed")).isEqualTo(1.0)
        assertThat(outcome("recorded")).isEqualTo(0.0)
    }

    @Test
    fun `a transient write failure is retried and then succeeds without throwing`(): Unit = runBlocking {
        var calls = 0
        coEvery { filings.observe(any()) } answers {
            calls++
            if (calls == 1) throw FilingStoreUnavailable("connection refused")
            true
        }

        consumer.consume(record(payload()))

        assertThat(calls).isEqualTo(2)
        assertThat(outcome("recorded")).isEqualTo(1.0)
        assertThat(outcome("failed")).isEqualTo(0.0)
    }

    private suspend fun consumeAllMalformed() {
        consumer.consume(record("not json"))
        consumer.consume(record("""{"remittanceId":"not-a-uuid"}"""))
        // A strictly-decoded amount: a silent zero here would file a return understating the tax.
        consumer.consume(
            record(
                """{"remittanceId":"$remittanceId","periodYear":2026,"periodMonth":7,"currency":"CZK",""" +
                    """"totalTaxAmount":"twelve","itemCount":9,"dueDate":"2026-08-20"}""",
            ),
        )
    }

    /**
     * The other half of the split, and the reason this is not a blanket rethrow: an event this
     * consumer cannot decode fails identically on every replay, so acking it is correct. Nothing
     * downstream is called, so nothing is silently half-done.
     *
     * This one is deliberately GREEN against the pre-fix consumer too — that is what makes the three
     * behaviour tests above meaningful rather than merely different. The poison-pill branch is the
     * part of the old design that was right, and this asserts the fix did not take it away.
     */
    @Test
    fun `a malformed payload is still acked and never reaches the filing service`(): Unit = runBlocking {
        consumeAllMalformed()

        coVerify(exactly = 0) { filings.observe(any()) }
    }

    /**
     * Separate from the ack assertion above on purpose: `malformed` is a NEW outcome label. Folding
     * it into the previous test would have made that test go red against the pre-fix consumer for a
     * reason that has nothing to do with acknowledgement, and the "green on both sides" claim above
     * would have been false.
     */
    @Test
    fun `a malformed payload is counted apart from a failed write`(): Unit = runBlocking {
        consumeAllMalformed()

        assertThat(outcome("malformed")).isEqualTo(3.0)
        // The distinction that matters operationally: `failed` now means "a write we gave up on and
        // rethrew", so it can be alerted on. Before, it meant that OR a bad payload, indiscriminately.
        assertThat(outcome("failed")).isEqualTo(0.0)
    }

    @Test
    fun `a record with another or no event type is ignored without decoding it`(): Unit = runBlocking {
        consumer.consume(record(payload(), eventType = "interest.accrual.posted.v1"))
        consumer.consume(record("not json at all", eventType = null))

        coVerify(exactly = 0) { filings.observe(any()) }
        assertThat(outcome("ignored_event_type")).isEqualTo(2.0)
    }

    @Test
    fun `an already-recorded batch is counted as a duplicate, not re-added to the return`(): Unit = runBlocking {
        coEvery { filings.observe(any()) } returns false

        consumer.consume(record(payload()))

        assertThat(outcome("duplicate")).isEqualTo(1.0)
        assertThat(outcome("recorded")).isEqualTo(0.0)
    }
}
