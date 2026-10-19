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
        // The replayed settle is counted asynchronously too; asserting it without a wait failed
        // intermittently in CI (#11536's build) while passing locally.
        awaitOutcomeAtLeast("unchanged", 1.0)

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

    @Test
    fun `an FX_SPOT deal is counted as unsupported, never stored, and does not stop the next deal`() {
        val source = connector.source<Any>("treasury-deal-in")
        val fxDeal = UUID.randomUUID()
        val fxPayload = """
            {"dealId":"$fxDeal","product":"FX_SPOT","counterpartyId":"CP1","currency":"EUR",
             "principal":10000.00,"rate":25.30,"valueDate":"$valueDate","maturityDate":"$valueDate",
             "occurredAt":"2026-09-25T07:40:00Z","sourceService":"treasury-service"}
        """.trimIndent()
        val before = outcome("unsupported_product")

        source.send(record("treasury.deal.settled.v1", fxPayload))
        await { outcome("unsupported_product") >= before + 1.0 }

        // Not stored: no row for the FX deal at all, under either state's SELECT window.
        assertThat(runBlocking { book.dealsOnBook(LocalDate.parse("2026-09-26")) }.map { it.dealId })
            .doesNotContain(fxDeal)
        assertThat(runBlocking { book.dealsOnBook(valueDate) }.map { it.dealId }).doesNotContain(fxDeal)
        assertThat(outcome("write_error")).isEqualTo(0.0)

        // The consumer keeps processing: a normal MM deal right after is still stored fine.
        source.send(record("treasury.deal.settled.v1", payload(""","ledgerJournalId":"${UUID.randomUUID()}"""")))
        awaitState("SETTLED")
        assertThat(runBlocking { book.dealsOnBook(LocalDate.parse("2026-09-26")) }.map { it.dealId }).contains(deal)
    }

    @Test
    fun `a HYPOTHETICAL deal is counted not_actual and never stored, while ACTUAL and absent kind are`() {
        val source = connector.source<Any>("treasury-deal-in")
        val hypothetical = UUID.randomUUID()
        val hypotheticalPayload = """
            {"dealId":"$hypothetical","product":"CNB_DEPOSIT_FACILITY","counterpartyId":"CNB","currency":"CZK",
             "principal":1000.00,"rate":2.50,"valueDate":"$valueDate","maturityDate":"2026-09-28",
             "createdBy":"d","approvedBy":"a","kind":"HYPOTHETICAL",
             "occurredAt":"2026-09-25T07:40:00Z","sourceService":"treasury-service"}
        """.trimIndent()
        val before = outcome("not_actual")
        source.send(record("treasury.deal.booked.v1", hypotheticalPayload))
        await { outcome("not_actual") >= before + 1.0 }
        assertThat(runBlocking { book.dealsOnBook(valueDate) }.map { it.dealId }).doesNotContain(hypothetical)
        assertThat(
            runBlocking { book.dealsOnBook(LocalDate.parse("2026-09-26")) }.map { it.dealId },
        ).doesNotContain(hypothetical)
        // An explicit ACTUAL is processed exactly like an event without the field.
        val actualSettled = payload(""","ledgerJournalId":"${UUID.randomUUID()}","kind":"ACTUAL"""")
        source.send(record("treasury.deal.settled.v1", actualSettled))
        awaitState("SETTLED")
    }

    /**
     * ADR-0315 D2: treasury now emits `treasury.deal.confirmed.v1` between booked and settled. It
     * moves nothing the engine models, so it is acked as `ignored` — never `malformed` — and the
     * deal it names keeps its state and rate. Remove the IGNORED_TYPES entry and this goes red on
     * the `malformed` counter.
     */
    @Test
    fun `a confirmed event is ignored - counted, not malformed, and the book is unchanged`() {
        val source = connector.source<Any>("treasury-deal-in")
        source.send(record("treasury.deal.settled.v1", payload(""","ledgerJournalId":"${UUID.randomUUID()}"""")))
        awaitState("SETTLED")
        val malformedBefore = outcome("malformed")
        val ignoredBefore = outcome("ignored")

        // Delivered late (after settled) on purpose: an ignored type must not move the state either way.
        source.send(
            record("treasury.deal.confirmed.v1", payload(""","confirmedBy":"bob.approver","simulated":false""")),
        )
        await { outcome("ignored") >= ignoredBefore + 1.0 }

        assertThat(outcome("malformed")).isEqualTo(malformedBefore)
        assertThat(current()!!.state).isEqualTo("SETTLED")
        // And the channel is not wedged: the matured event right after still lands.
        source.send(record("treasury.deal.matured.v1", payload(""","interest":694.44""")))
        awaitState("MATURED")
    }

    /**
     * ADR-0315 D7: treasury also publishes `treasury.nostro.break-aged.v1` on this topic. It names
     * no deal, so it is `ignored` — never `malformed`, which would report a producer defect on
     * every aged nostro break. Remove the IGNORED_TYPES entry and this goes red on `malformed`.
     */
    @Test
    fun `a nostro break-aged event is ignored, not malformed`() {
        val source = connector.source<Any>("treasury-deal-in")
        val malformedBefore = outcome("malformed")
        val ignoredBefore = outcome("ignored")
        source.send(
            record(
                "treasury.nostro.break-aged.v1",
                """{"breakId":"${UUID.randomUUID()}","iban":"CZ1299990000000000001001","glCode":"1001",""" +
                    """"currency":"CZK","side":"STATEMENT","ourSide":"DEBIT","amount":5000.00,""" +
                    """"bookingDate":"2026-09-25","firstSeenOn":"2026-09-25","ageBusinessDays":3,""" +
                    """"thresholdDays":3,"occurredAt":"2026-09-30T10:00:00Z","sourceService":"treasury-service"}""",
            ),
        )
        await { outcome("ignored") >= ignoredBefore + 1.0 }
        assertThat(outcome("malformed")).isEqualTo(malformedBefore)
    }

    @Inject
    lateinit var cashFlows: com.openbank.risk.application.port.out.TreasuryCashFlowStore

    private val irs = UUID.randomUUID()
    private val overBound = UUID.randomUUID()

    /**
     * ADR-0344 D2: a NEW-format event (kind, bookId, legs) for a product the snapshot does not
     * model is stored for valuation with its legs; the OLD format (no new fields) still parses as an
     * ACTUAL deal without legs — the legacy money-market product derives its legs at read time.
     */
    @Test
    fun `a new-format IRS event is stored with its legs, an old-format event still parses`() {
        val source = connector.source<Any>("treasury-deal-in")
        val legs = """[{"payReceive":"PAY","currency":"CZK","notional":5000000,"accrualStart":"2030-01-10",
            "accrualEnd":"2032-01-10","accrualFrequency":"1Y","dayCount":"ACT/360","rate":{"type":"FIXED","rate":0.035},
            "finalNotionalExchange":false,"someFutureField":{"x":1}}]"""
        source.send(
            record(
                "treasury.deal.booked.v1",
                """{"dealId":"$irs","product":"IRS","counterpartyId":"CP1","currency":"CZK","principal":5000000,
                    "valueDate":"2030-01-10","maturityDate":"2032-01-10","kind":"PLANNED","bookId":"ALM-1",
                    "calendars":["CZK"],"scheduleHash":"sha256:abc","legs":$legs,"createdBy":"d","approvedBy":"a"}""",
            ),
        )
        source.send(record("treasury.deal.settled.v1", payload(""","ledgerJournalId":"${UUID.randomUUID()}"""")))
        await { runBlocking { book.valuationCandidates(LocalDate.parse("2030-06-01")) }.any { it.dealId == irs } }
        awaitState("SETTLED")
        val stored = runBlocking { book.valuationCandidates(LocalDate.parse("2030-06-01")) }.single { it.dealId == irs }
        assertThat(stored.kind.name).isEqualTo("PLANNED")
        assertThat(stored.bookId).isEqualTo("ALM-1")
        assertThat(stored.legs).hasSize(1)
        assertThat(stored.legs!!.single().notional.amount).isEqualByComparingTo("5000000")
        assertThat(stored.scheduleHash).isEqualTo("sha256:abc")
        // A PLANNED deal never enters the snapshot's instruments.
        assertThat(
            runBlocking {
                book.dealsOnBook(LocalDate.parse("2030-06-01"))
            }.map { it.dealId },
        ).doesNotContain(irs)
        val legacy = current()!!
        assertThat(legacy.kind.name).isEqualTo("ACTUAL")
        assertThat(legacy.legs).isNull()

        // Legs libs-finance cannot build (here: a daily leg over 60 years, past the period cap) are a
        // real BOOKED position: stored as unvaluable with the reason and counted — never acked-and-dropped.
        val unvaluableBefore = outcome("stored_unvaluable")
        val malformedBefore = outcome("malformed")
        source.send(
            record(
                "treasury.deal.booked.v1",
                """{"dealId":"$overBound","product":"IRS","counterpartyId":"CP1","currency":"CZK",
                    "principal":1000000,"valueDate":"2030-01-10","maturityDate":"2090-01-10","legs":[{"payReceive":"PAY",
                    "currency":"CZK","notional":1000000,"accrualStart":"2030-01-10","accrualEnd":"2090-01-10",
                    "accrualFrequency":"1D","rate":{"type":"FIXED","rate":0.03}}]}""",
            ),
        )
        await { outcome("stored_unvaluable") >= unvaluableBefore + 1.0 }
        val kept = runBlocking { book.valuationCandidates(LocalDate.parse("2030-06-01")) }.single {
            it.dealId ==
                overBound
        }
        assertThat(kept.legs).isNull()
        assertThat(kept.legsError).contains("exceeds 2000 periods")
        assertThat(outcome("malformed")).isEqualTo(malformedBefore)
    }

    /** ADR-0344 D5: a fixing patches the stored set of the SAME deal version; another version is a no-op. */
    @Test
    fun `a cash-flow fixing patches the stored floating flow of the same deal version`() {
        val deal = UUID.randomUUID()
        val source = connector.source<Any>("treasury-deal-in")
        fun row(state: String, amount: String, rateSource: String) = """{"flowId":"${UUID.randomUUID()}","legIndex":1,
            "periodIndex":0,"kind":"INTEREST","direction":"IN","currency":"CZK","accrualStart":"2030-01-10",
            "accrualEnd":"2030-04-10","paymentDate":"2030-04-10","rate":0.0362,"rateSource":"$rateSource",
            "amount":$amount,"state":"$state"}"""
        source.send(
            record(
                "treasury.cashflow.generated.v1",
                """{"dealId":"$deal","dealVersion":3,"scheduleHash":"h3","flows":[${row(
                    "PROJECTED",
                    "null",
                    "PROJECTED",
                )}]}""",
            ),
        )
        await { runBlocking { cashFlows.latest(listOf(deal)) }[deal]?.dealVersion == 3 }
        val fixed = """{"dealId":"$deal","dealVersion":3,"flows":[${row("FIXED", "90500.00", "OBSERVED")}]}"""
        source.send(record("treasury.cashflow.fixed.v1", fixed))
        await { runBlocking { cashFlows.latest(listOf(deal)) }.getValue(deal).flows.single().state == "FIXED" }
        val stored = runBlocking { cashFlows.latest(listOf(deal)) }.getValue(deal).flows.single()
        assertThat(stored.amount).isEqualByComparingTo("90500.00")
        assertThat(stored.isFact).isTrue()
        // A fixing of another version changes nothing; a PROJECTED row in a fixed event is malformed.
        val unchangedBefore = outcome("unchanged")
        source.send(record("treasury.cashflow.fixed.v1", fixed.replace("\"dealVersion\":3", "\"dealVersion\":2")))
        await { outcome("unchanged") >= unchangedBefore + 1.0 }
        val malformedBefore = outcome("malformed")
        source.send(
            record(
                "treasury.cashflow.fixed.v1",
                """{"dealId":"$deal","dealVersion":3,"flows":[${row("PROJECTED", "null", "PROJECTED")}]}""",
            ),
        )
        await { outcome("malformed") >= malformedBefore + 1.0 }
    }

    /** ADR-0344 D4: the stored ledger is kept at the latest version; an older version is a no-op. */
    @Test
    fun `cash-flow events keep the latest deal version only`() {
        val source = connector.source<Any>("treasury-deal-in")
        fun flows(version: Int, amount: String) = """{"dealId":"$irs","dealVersion":$version,"scheduleHash":"h$version",
            "generatedAt":"2030-01-10T08:00:00Z","flows":[{"flowId":"${UUID.randomUUID()}","legIndex":0,"periodIndex":0,
            "kind":"INTEREST","direction":"OUT","currency":"CZK","accrualStart":"2030-01-10","accrualEnd":"2031-01-10",
            "paymentDate":"2031-01-10","notional":5000000,"rate":0.035,"rateSource":"FIXED","amount":$amount,
            "state":"FIXED","manual":false,"journalRefs":[]}]}"""
        source.send(record("treasury.cashflow.generated.v1", flows(2, "177430.56")))
        await { runBlocking { cashFlows.latest(listOf(irs)) }[irs]?.dealVersion == 2 }
        val unchangedBefore = outcome("unchanged")
        source.send(record("treasury.cashflow.generated.v1", flows(1, "1.00")))
        await { outcome("unchanged") >= unchangedBefore + 1.0 }
        val latest = runBlocking { cashFlows.latest(listOf(irs)) }.getValue(irs)
        assertThat(latest.dealVersion).isEqualTo(2)
        assertThat(latest.flows.single().signedAmount).isEqualByComparingTo("-177430.56")
        val malformedBefore = outcome("malformed")
        source.send(record("treasury.cashflow.generated.v1", """{"dealId":"$irs","flows":[]}"""))
        await { outcome("malformed") >= malformedBefore + 1.0 }
    }

    @org.junit.jupiter.api.AfterEach
    fun cleanup() {
        TestDb.execute("DELETE FROM treasury_deal WHERE deal_id IN ('$deal', '$irs', '$overBound')")
        TestDb.execute("DELETE FROM treasury_deal_cash_flows WHERE deal_id = '$irs'")
    }

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
