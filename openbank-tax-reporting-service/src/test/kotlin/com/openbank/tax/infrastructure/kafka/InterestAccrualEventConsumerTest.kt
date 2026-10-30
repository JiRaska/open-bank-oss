// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.messaging.EventRetry
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.tax.application.usecase.IncomeCreditService
import com.openbank.tax.application.usecase.TaxFilingService
import com.openbank.tax.domain.model.ObservedRemittance
import com.openbank.tax.domain.returns.ObservedIncomeCredit
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
 * The two filing consumers' shared failure contract (#5698/#5745, ADR-0340 B).
 *
 * One channel (`withholding-remitted-in`), two event types dispatched on the `ce-type` header:
 *  - `interest.withholding.remitted.v1` → the §38d monthly remittance filing; and
 *  - `interest.withholding.recorded.v1` → gross interest credit per account for the CRS/FATCA
 *    information-exchange assembly.
 *
 * The untested branch that matters in both: a failed write was counted and acked. `assemble` totals
 * only what was *observed*, so a lost event never shows up as an error or a gap — it silently
 * understates the return that then gets filed, and `auto.offset.reset: latest` rules out recovering
 * it by replay.
 *
 * Both persistent write failures and malformed target records must reach the configured DLQ;
 * neither may be acknowledged as a successful observation.
 */
/** A named transient failure, so the tests below never `throw RuntimeException` (detekt). */
private class FilingStoreUnavailable(message: String) : RuntimeException(message)

class InterestAccrualEventConsumerTest {

    private val filings = mockk<TaxFilingService>()
    private val incomeCredits = mockk<IncomeCreditService>()
    private val registry = SimpleMeterRegistry()
    private val clock = Clock.fixed(Instant.parse("2026-08-05T09:00:00Z"), ZoneOffset.UTC)
    private val consumer = InterestAccrualEventConsumer(filings, incomeCredits, ObjectMapper(), registry, clock)

    private val remittanceId = UUID.randomUUID()
    private val withholdingId = UUID.randomUUID()

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

    private fun remittedPayload(id: UUID = remittanceId) = """
        {"remittanceId":"$id","periodYear":2026,"periodMonth":7,"currency":"CZK",
         "totalTaxAmount":"1234.56","itemCount":9,"dueDate":"2026-08-20"}
    """.trimIndent()

    private fun recordedPayload(id: UUID = withholdingId) = """
        {"withholdingId":"$id","capitalizationId":"6f7c5c54-1b3b-4c1a-9a5a-7d3e2f1a0b2c",
         "accountId":"4f2d8a3e-5c1b-4e8a-9f7b-2a6d0c4e8b1d",
         "productId":"9a8b7c6d-5e4f-4a3b-2c1d-0e9f8a7b6c5d",
         "periodFrom":"2026-07-01","periodTo":"2026-07-31","currency":"CZK",
         "grossAmount":"100.00","netAmount":"85.00","occurredAt":"2026-07-31T23:59:59Z"}
    """.trimIndent()

    private fun outcome(value: String): Double =
        registry.counter("openbank.tax.withholding_events", "outcome", value).count()

    @Test
    fun `a remitted event is observed into the filing period`(): Unit = runBlocking {
        coEvery { filings.observe(any()) } returns true

        consumer.consume(record(remittedPayload()))

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

        assertThrows<FilingStoreUnavailable> { runBlocking { consumer.consume(record(remittedPayload())) } }

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

        consumer.consume(record(remittedPayload()))

        assertThat(calls).isEqualTo(2)
        assertThat(outcome("recorded")).isEqualTo(1.0)
        assertThat(outcome("failed")).isEqualTo(0.0)
    }

    private suspend fun consumeAllMalformed() {
        assertThrows<IllegalArgumentException> { runBlocking { consumer.consume(record("not json")) } }
        assertThrows<IllegalArgumentException> {
            runBlocking { consumer.consume(record("""{"remittanceId":"not-a-uuid"}""")) }
        }
        // A strictly-decoded amount: a silent zero here would file a return understating the tax.
        val malformedAmount = record(
            """{"remittanceId":"$remittanceId","periodYear":2026,"periodMonth":7,"currency":"CZK",""" +
                """"totalTaxAmount":"twelve","itemCount":9,"dueDate":"2026-08-20"}""",
        )
        val failure = assertThrows<IllegalArgumentException> {
            runBlocking { consumer.consume(malformedAmount) }
        }
        assertThat(failure.message).doesNotContain("twelve", remittanceId.toString())
        assertThat(failure.cause).isNull()
        assertThat(malformedAmount.value()).contains("twelve")
        assertThat(malformedAmount.headers().lastHeader(OutboxKafkaHeaders.HEADER_EVENT_TYPE)).isNotNull()
    }

    /**
     * A malformed target event must be nacked so the original bytes survive on the DLQ, and it
     * must never reach the filing service with a zero or partial amount.
     */
    @Test
    fun `a malformed target payload is nacked and never reaches the filing service`(): Unit = runBlocking {
        consumeAllMalformed()

        coVerify(exactly = 0) { filings.observe(any()) }
    }

    /**
     * Keep decode failures separate from storage failures for alert diagnosis. Both are nacked.
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
        consumer.consume(record(remittedPayload(), eventType = "interest.accrual.posted.v1"))
        consumer.consume(record("not json at all", eventType = null))

        coVerify(exactly = 0) { filings.observe(any()) }
        coVerify(exactly = 0) { incomeCredits.record(any()) }
        assertThat(outcome("ignored_event_type")).isEqualTo(2.0)
    }

    @Test
    fun `an already-recorded batch is counted as a duplicate, not re-added to the return`(): Unit = runBlocking {
        coEvery { filings.observe(any()) } returns false

        consumer.consume(record(remittedPayload()))

        assertThat(outcome("duplicate")).isEqualTo(1.0)
        assertThat(outcome("recorded")).isEqualTo(0.0)
    }

    @Test
    fun `a recorded event is stored for the CRS FATCA assembly`(): Unit = runBlocking {
        coEvery { incomeCredits.record(any()) } returns true

        consumer.consume(record(recordedPayload(), eventType = "interest.withholding.recorded.v1"))

        coVerify(exactly = 1) {
            incomeCredits.record(
                match<ObservedIncomeCredit> {
                    it.withholdingId == withholdingId &&
                        it.accountId.toString() == "4f2d8a3e-5c1b-4e8a-9f7b-2a6d0c4e8b1d" &&
                        it.grossAmount.toPlainString() == "100.00" &&
                        it.netAmount.toPlainString() == "85.00" &&
                        it.periodFrom.toString() == "2026-07-01" &&
                        it.periodTo.toString() == "2026-07-31" &&
                        it.occurredAt == Instant.parse("2026-07-31T23:59:59Z") &&
                        it.observedAt == Instant.parse("2026-08-05T09:00:00Z")
                },
            )
        }
        coVerify(exactly = 0) { filings.observe(any()) }
        assertThat(outcome("recorded")).isEqualTo(1.0)
    }

    @Test
    fun `an already-recorded credit is counted as a duplicate`(): Unit = runBlocking {
        coEvery { incomeCredits.record(any()) } returns false

        consumer.consume(record(recordedPayload(), eventType = "interest.withholding.recorded.v1"))

        assertThat(outcome("duplicate")).isEqualTo(1.0)
        assertThat(outcome("recorded")).isEqualTo(0.0)
    }

    @Test
    fun `a failed income-credit write is retried and rethrown`(): Unit = runBlocking {
        coEvery { incomeCredits.record(any()) } throws FilingStoreUnavailable("tax-db down")

        assertThrows<FilingStoreUnavailable> {
            runBlocking { consumer.consume(record(recordedPayload(), eventType = "interest.withholding.recorded.v1")) }
        }

        coVerify(exactly = EventRetry.DEFAULT_MAX_ATTEMPTS) { incomeCredits.record(any()) }
        assertThat(outcome("failed")).isEqualTo(1.0)
        assertThat(outcome("recorded")).isEqualTo(0.0)
    }

    private suspend fun consumeAllMalformedRecorded() {
        assertThrows<IllegalArgumentException> {
            runBlocking { consumer.consume(record("not json", eventType = "interest.withholding.recorded.v1")) }
        }
        assertThrows<IllegalArgumentException> {
            runBlocking {
                consumer.consume(
                    record(
                        """{"withholdingId":"not-a-uuid"}""",
                        eventType = "interest.withholding.recorded.v1",
                    ),
                )
            }
        }
        // Strict decode again: an unparsable gross amount must never land in a CRS report as zero.
        val malformedAmount = record(
            """{"withholdingId":"$withholdingId","capitalizationId":"6f7c5c54-1b3b-4c1a-9a5a-7d3e2f1a0b2c",""" +
                """"accountId":"4f2d8a3e-5c1b-4e8a-9f7b-2a6d0c4e8b1d",""" +
                """"productId":"9a8b7c6d-5e4f-4a3b-2c1d-0e9f8a7b6c5d",""" +
                """"periodFrom":"2026-07-01","periodTo":"2026-07-31","currency":"CZK",""" +
                """"grossAmount":"hundred","netAmount":"85.00","occurredAt":"2026-07-31T23:59:59Z"}""",
            eventType = "interest.withholding.recorded.v1",
        )
        val failure = assertThrows<IllegalArgumentException> {
            runBlocking { consumer.consume(malformedAmount) }
        }
        assertThat(failure.message).doesNotContain("hundred", withholdingId.toString())
        assertThat(failure.cause).isNull()
    }

    /**
     * The recorded-event mirror of the remitted contract: a malformed credit is nacked, counted
     * `malformed` (never `failed`), and never reaches the store with a zero or partial amount —
     * a silently-zeroed gross amount would understate the CRS income figure.
     */
    @Test
    fun `a malformed recorded payload is nacked and never reaches the store`(): Unit = runBlocking {
        consumeAllMalformedRecorded()

        coVerify(exactly = 0) { incomeCredits.record(any()) }
        assertThat(outcome("malformed")).isEqualTo(3.0)
        assertThat(outcome("failed")).isEqualTo(0.0)
    }
}
