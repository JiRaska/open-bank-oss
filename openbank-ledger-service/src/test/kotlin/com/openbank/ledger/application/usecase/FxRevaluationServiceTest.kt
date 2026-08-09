// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.openbank.ledger.application.port.`in`.GetTrialBalanceQuery
import com.openbank.ledger.application.port.`in`.LedgerUseCase
import com.openbank.ledger.application.port.`in`.PostJournalCommand
import com.openbank.ledger.application.port.`in`.RevalueFxCommand
import com.openbank.ledger.application.port.out.CnbFixing
import com.openbank.ledger.application.port.out.CnbRateProvider
import com.openbank.ledger.application.port.out.GlAccountRepository
import com.openbank.ledger.domain.model.GlAccount
import com.openbank.ledger.domain.model.GlAccountType
import com.openbank.ledger.domain.model.JournalEntry
import com.openbank.ledger.domain.model.JournalLine
import com.openbank.ledger.domain.model.JournalSide
import com.openbank.ledger.domain.model.JournalStatus
import com.openbank.ledger.domain.model.TrialBalance
import com.openbank.ledger.domain.model.TrialBalanceLine
import com.openbank.ledger.infrastructure.observability.FxFixingFreshnessGauge
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.Money
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class FxRevaluationServiceTest {

    private val date = LocalDate.of(2026, 5, 30)
    private val ledger = mockk<LedgerUseCase>()
    private val glAccounts = mockk<GlAccountRepository>()
    private val cnbRates = mockk<CnbRateProvider>()

    // Mirrors Quarkus's managed ObjectMapper (JavaTimeModule + dates-as-ISO-strings), matching
    // LedgerServiceTest's own jsonMapper convention — without it, serializing occurredAt (Instant)
    // throws (Instant has no default Jackson handler).
    private val objectMapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    private val now = Instant.parse("2026-05-30T15:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    // The REAL Micrometer adapter over a SimpleMeterRegistry, not a mock port: the assertions
    // below are on published gauge values, and a mock would only prove the call was made — which
    // says nothing about what an alert would read at scrape time (#3921).
    private val registry = SimpleMeterRegistry()
    private val freshness = FxFixingFreshnessGauge(registry, clock)

    private val service = FxRevaluationService(ledger, glAccounts, cnbRates, objectMapper, clock, freshness)

    /** ČNB publishes the fixing early afternoon Prague time; the date is what matters. */
    private fun fixing(rate: String, validFrom: Instant? = Instant.parse("2026-05-30T13:15:00Z")) =
        CnbFixing(BigDecimal(rate), validFrom)

    /** Mirrors FxRevaluationService's key suffix, computed independently from the canonical form. */
    private fun digestOf(canonical: String): String = MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(12)

    private fun age(currency: String): Double? = registry.find(FxFixingFreshnessGauge.FIXING_AGE_SECONDS)
        .tag(FxFixingFreshnessGauge.CURRENCY_TAG, currency)
        .gauge()
        ?.value()

    private val eurCvId = UUID.randomUUID()
    private val pnlId = UUID.randomUUID()

    private fun gl(id: UUID, code: String) = GlAccount(
        id = id, code = code, name = code, type = GlAccountType.ASSET,
        currency = CurrencyCode.of("CZK"), parentId = null, isLeaf = true, isEnabled = true,
        createdAt = Instant.now(),
    )

    private fun position(code: String, debit: String, credit: String) = TrialBalanceLine(
        glAccountId = UUID.randomUUID(),
        code = code,
        name = code,
        type = GlAccountType.ASSET,
        currency = if (code.startsWith("199") && code != "1990" && code != "1995") code.foreignCcy() else "CZK",
        totalDebit = BigDecimal(debit),
        totalCredit = BigDecimal(credit),
    )

    private fun String.foreignCcy() = when (this) {
        "1991" -> "EUR"
        "1992" -> "USD"
        "1993" -> "GBP"
        else -> "CZK"
    }

    /**
     * Trial balance for a long 1,000,000 EUR position whose counter-value account already carries
     * [carryCzk] — i.e. what the ledger looks like AFTER `carryCzk` has been marked. Cumulative to
     * the business day (`entry_date <= :asOf`), which is why a correcting run sees its own
     * predecessor here rather than a clean slate.
     */
    private fun longEurWithCarry(carryCzk: String) = TrialBalance(
        asOf = date,
        lines = listOfNotNull(
            position("1991", debit = "0", credit = "1000000"),
            if (carryCzk == "0") {
                null
            } else {
                TrialBalanceLine(
                    UUID.randomUUID(),
                    "1995",
                    "1995",
                    GlAccountType.ASSET,
                    "CZK",
                    BigDecimal(carryCzk),
                    BigDecimal.ZERO,
                )
            },
        ),
    )

    private fun stubEntry(): JournalEntry {
        val id = UUID.randomUUID()
        val a =
            JournalLine(
                UUID.randomUUID(),
                id,
                eurCvId,
                JournalSide.DEBIT,
                Money.of("100.00", "CZK"),
                null,
                Money.of("100.00", "CZK"),
                1,
            )
        val b =
            JournalLine(
                UUID.randomUUID(),
                id,
                pnlId,
                JournalSide.CREDIT,
                Money.of("100.00", "CZK"),
                null,
                Money.of("100.00", "CZK"),
                2,
            )
        return JournalEntry(
            id, 1L, UUID.randomUUID(), date, date, "stub", JournalStatus.POSTED,
            listOf(
                a,
                b,
            ),
            Instant.now(), UUID.randomUUID(), 0L,
        )
    }

    @Test
    fun `marks a long EUR position and posts an idempotent fx-reval entry`() = runBlocking<Unit> {
        // Bank long 1,000,000 EUR: the 199x position account is CREDITED on acquisition.
        coEvery { ledger.getTrialBalance(GetTrialBalanceQuery(date)) } returns TrialBalance(
            asOf = date,
            lines = listOf(position("1991", debit = "0", credit = "1000000")),
        )
        coEvery { cnbRates.cnbRate("EUR", date) } returns fixing("25.145")
        coEvery { cnbRates.cnbRate("USD", date) } returns null
        coEvery { cnbRates.cnbRate("GBP", date) } returns null
        coEvery { glAccounts.findByCode("1995") } returns gl(eurCvId, "1995")
        coEvery { glAccounts.findByCode("5900") } returns gl(pnlId, "5900")
        val cmd = slot<PostJournalCommand>()
        val entry = stubEntry()
        coEvery { ledger.postJournal(capture(cmd)) } returns entry

        val result = service.revalue(RevalueFxCommand(date))

        assertThat(result.posted).isTrue()
        assertThat(result.movements).containsEntry("EUR", BigDecimal("25145000.00"))
        assertThat(result.movements).doesNotContainKeys("USD", "GBP")
        // 1,000,000 * 25.145 = 25,145,000 CZK marked from a zero carry.
        // #3921: the key is no longer the business day ALONE. This assertion used to read
        // `isEqualTo("fx-reval-2026-05-30")` — it encoded the defect as the expected behaviour,
        // so the single-posting bug had a green test defending it. The day is still the prefix
        // (an operator grepping for a date still finds the entry); the suffix is the identity of
        // the fixings this posting was built from, and `a corrected fixing posts a superseding
        // entry` below is what proves it discriminates.
        assertThat(cmd.captured.idempotencyKey).startsWith("fx-reval-2026-05-30-")
        // Stable and derived, not random: the same fixing set must key the same way on a re-run,
        // or the idempotency guard stops guarding anything.
        assertThat(cmd.captured.idempotencyKey).isEqualTo(
            "fx-reval-2026-05-30-" + digestOf("EUR@2026-05-30T13:15:00Z"),
        )
        assertThat(cmd.captured.lines).hasSize(2)
        assertThat(cmd.captured.lines.all { it.baseCurrencyCode == "CZK" }).isTrue()

        // FxRevalued rides the SAME outbox transaction as the journal post (#1201 proposed fix
        // 3), not a separate post-commit publish a crash could lose — proven here by asserting on
        // the outbox message the command carries, not a call to some now-deleted publisher.
        val outboxMessages = cmd.captured.additionalOutboxMessages(entry)
        assertThat(outboxMessages).hasSize(1)
        val message = outboxMessages.single()
        assertThat(message.aggregateId).isEqualTo(entry.id)
        assertThat(message.eventType).isEqualTo("FxRevalued")
        val node = objectMapper.readTree(message.payload)
        assertThat(node["aggregateId"].asText()).isEqualTo(entry.id.toString())
        assertThat(node["date"].asText()).isEqualTo(date.toString())
        // decimalValue(), not asText(): Jackson's default BigDecimal serialization uses
        // scientific notation for a value this large (pre-existing, unrelated to this change —
        // AccountBookedChangedEvent's payload has the same characteristic, no ObjectMapperCustomizer
        // overrides it), so the JSON node's text form is "2.5145E7", not "25145000.00" — the same
        // numeric value, different string representation. A caller reading this back with a real
        // JSON parser (as any consumer must) gets the same BigDecimal either way.
        assertThat(node["movements"]["EUR"].decimalValue()).isEqualByComparingTo(BigDecimal("25145000.00"))
    }

    @Test
    fun `posts nothing when no position has moved`() = runBlocking<Unit> {
        coEvery { ledger.getTrialBalance(GetTrialBalanceQuery(date)) } returns TrialBalance(
            asOf = date,
            lines = listOf(
                position("1991", debit = "0", credit = "1000000"),
                // Counter-value already holds exactly 1,000,000 * 25.145.
                TrialBalanceLine(
                    UUID.randomUUID(),
                    "1995",
                    "1995",
                    GlAccountType.ASSET,
                    "CZK",
                    BigDecimal("25145000.00"),
                    BigDecimal.ZERO,
                ),
            ),
        )
        coEvery { cnbRates.cnbRate("EUR", date) } returns fixing("25.145")
        coEvery { cnbRates.cnbRate("USD", date) } returns null
        coEvery { cnbRates.cnbRate("GBP", date) } returns null
        coEvery { glAccounts.findByCode("1995") } returns gl(eurCvId, "1995")

        val result = service.revalue(RevalueFxCommand(date))

        assertThat(result.posted).isFalse()
        assertThat(result.movements).isEmpty()
        coVerify(exactly = 0) { ledger.postJournal(any()) }
    }

    // ── #3921: the fixing's age is observable, and the entry says which fixing it used ────────

    @Test
    fun `publishes the age of the fixing each leg was marked at, and records it on the entry`() = runBlocking<Unit> {
        // A Friday fixing marking a Monday position: three days old, inside fx-service's
        // date-blind three-day validity window, so nothing else on the run reports a problem.
        val friday = now.minus(Duration.ofDays(3))
        coEvery { ledger.getTrialBalance(GetTrialBalanceQuery(date)) } returns TrialBalance(
            asOf = date,
            lines = listOf(position("1991", debit = "0", credit = "1000000")),
        )
        coEvery { cnbRates.cnbRate("EUR", date) } returns fixing("25.145", validFrom = friday)
        coEvery { cnbRates.cnbRate("USD", date) } returns null
        coEvery { cnbRates.cnbRate("GBP", date) } returns null
        coEvery { glAccounts.findByCode("1995") } returns gl(eurCvId, "1995")
        coEvery { glAccounts.findByCode("5900") } returns gl(pnlId, "5900")
        val cmd = slot<PostJournalCommand>()
        coEvery { ledger.postJournal(capture(cmd)) } returns stubEntry()

        val result = service.revalue(RevalueFxCommand(date))

        // The run itself is a perfectly ordinary success — that is the point.
        assertThat(result.posted).isTrue()
        // The age is the FIXING's, not the run's: 3 days, not ~0.
        assertThat(age("EUR")).isEqualTo(Duration.ofDays(3).seconds.toDouble())
        // And the posting now answers "which rate valued this position", which the ledger
        // could not answer at all before — the question that matters the moment a corrected
        // fixing arrives after the run.
        assertThat(cmd.captured.description)
            .isEqualTo("Daily FX revaluation at ČNB fixing 2026-05-30 [fixings EUR@2026-05-27T15:00:00Z]")
    }

    @Test
    fun `a currency with no fixing still publishes an age, so a quiet feed is visible`() = runBlocking<Unit> {
        coEvery { ledger.getTrialBalance(GetTrialBalanceQuery(date)) } returns TrialBalance(
            asOf = date,
            lines = listOf(position("1991", debit = "0", credit = "1000000")),
        )
        coEvery { cnbRates.cnbRate("EUR", date) } returns fixing("25.145")
        coEvery { cnbRates.cnbRate("USD", date) } returns null
        coEvery { cnbRates.cnbRate("GBP", date) } returns null
        coEvery { glAccounts.findByCode("1995") } returns gl(eurCvId, "1995")
        coEvery { glAccounts.findByCode("5900") } returns gl(pnlId, "5900")
        coEvery { ledger.postJournal(any()) } returns stubEntry()

        service.revalue(RevalueFxCommand(date))

        // USD and GBP resolved to nothing, and each still has a series. An absent series is
        // how "no rate for two days" reads as "nothing to see" on every dashboard.
        assertThat(age("USD")).isNotNull()
        assertThat(age("GBP")).isNotNull()
        assertThat(
            registry.find(FxFixingFreshnessGauge.FIXING_AGE_SECONDS).gauges().map { it.id.getTag("currency") },
        ).containsExactlyInAnyOrder("EUR", "USD", "GBP")
    }

    // ── #3921 correctness half: a corrected fixing supersedes, and the mark is date-correct ──

    /**
     * The defect this closes, end to end and in the order it happens in production.
     *
     * Run 1 marks a 1,000,000 EUR position at 25.145 from a zero carry: 25,145,000 CZK. ČNB then
     * publishes a correction for the SAME business day, 25.500. Run 2 sees the trial balance the
     * first posting left behind (`entry_date <= asOf` — the counter-value account now nets
     * 25,145,000) and must post the DIFFERENCE, 355,000 CZK, under a different key.
     *
     * Against `origin/main` this fails on the very first assertion of run 2: the key is
     * `fx-reval-2026-05-30` both times, `postJournal` returns the original entry on the key hit,
     * and the run reports `posted = true` having changed nothing.
     *
     * The 355,000 is the whole argument for a superseding entry over a reversal. 25,145,000 +
     * 355,000 = 25,500,000 = the corrected mark exactly — the position is right after two entries
     * because the movement is computed from the carry, not from zero. A key change without that
     * property would have posted 25,500,000 on top of 25,145,000 and doubled the position.
     */
    @Test
    fun `a corrected fixing posts a superseding entry for the difference, under a different key`() = runBlocking<Unit> {
        coEvery { cnbRates.cnbRate("USD", date) } returns null
        coEvery { cnbRates.cnbRate("GBP", date) } returns null
        coEvery { glAccounts.findByCode("1995") } returns gl(eurCvId, "1995")
        coEvery { glAccounts.findByCode("5900") } returns gl(pnlId, "5900")
        val commands = mutableListOf<PostJournalCommand>()
        coEvery { ledger.postJournal(capture(commands)) } returns stubEntry()

        // ── Run 1: the original fixing, marked from a zero carry.
        coEvery { ledger.getTrialBalance(GetTrialBalanceQuery(date)) } returns longEurWithCarry("0")
        coEvery { cnbRates.cnbRate("EUR", date) } returns fixing("25.145")

        val first = service.revalue(RevalueFxCommand(date))
        assertThat(first.movements).containsEntry("EUR", BigDecimal("25145000.00"))

        // ── ČNB corrects the fixing for the same day, published two hours later.
        val corrected = Instant.parse("2026-05-30T15:15:00Z")
        coEvery { cnbRates.cnbRate("EUR", date) } returns fixing("25.500", validFrom = corrected)
        // The trial balance now includes run 1's own posting — this is the fact the whole
        // design rests on, and stubbing it is what makes the test model production rather
        // than a convenient fiction.
        coEvery { ledger.getTrialBalance(GetTrialBalanceQuery(date)) } returns
            longEurWithCarry("25145000.00")

        val second = service.revalue(RevalueFxCommand(date))

        // It posted at all — on origin/main the second run never reaches a new key.
        assertThat(commands).hasSize(2)
        assertThat(second.posted).isTrue()
        // The DIFFERENCE, not the full corrected mark: 25,500,000 − 25,145,000.
        assertThat(second.movements).containsEntry("EUR", BigDecimal("355000.00"))
        // 25,145,000 + 355,000 == the corrected mark. No double count.
        assertThat(first.movements.getValue("EUR").add(second.movements.getValue("EUR")))
            .isEqualByComparingTo(BigDecimal("25500000.00"))
        // Different key, so postJournal cannot short-circuit it as a replay...
        assertThat(commands[1].idempotencyKey).isNotEqualTo(commands[0].idempotencyKey)
        // ...and both keys still name the business day, so the pair is greppable as one day.
        assertThat(commands.map { it.idempotencyKey })
            .allSatisfy { assertThat(it).startsWith("fx-reval-2026-05-30-") }
        // The entry says which fixing superseded which, in full — the key's digest is
        // deliberately opaque and this is where it is resolved.
        assertThat(commands[1].description).contains("[fixings EUR@2026-05-30T15:15:00Z]")
        // A THIRD run at the same corrected fixing must change nothing — and what stops it is
        // NOT the key. Once the carry reflects both postings the movement is zero, so the leg
        // contributes nothing and the run returns before `postJournal` is called at all. This
        // is why widening the key does not reopen the double-posting it was guarding against.
        coEvery { ledger.getTrialBalance(GetTrialBalanceQuery(date)) } returns
            longEurWithCarry("25500000.00")

        val third = service.revalue(RevalueFxCommand(date))

        assertThat(third.posted).isFalse()
        assertThat(third.movements).isEmpty()
        assertThat(commands).hasSize(2)
    }

    /**
     * Step 3 of #3921: the rate is resolved AS OF the business day being marked, not "now".
     *
     * A strict-argument stub is the assertion — mockk fails an unstubbed call — so this pins that
     * a backfill of 2026-05-27 asks fx-service for 2026-05-27's fixing. On `origin/main` the port
     * takes the currency alone, so this defect is only expressible once the parameter exists; that
     * is why the issue called step 1 the prerequisite for everything after it.
     */
    @Test
    fun `a backfill asks for the fixing in effect on the day it is marking, not today`() = runBlocking<Unit> {
        val backfillDate = LocalDate.of(2026, 5, 27)
        coEvery { ledger.getTrialBalance(GetTrialBalanceQuery(backfillDate)) } returns TrialBalance(
            asOf = backfillDate,
            lines = listOf(position("1991", debit = "0", credit = "1000000")),
        )
        coEvery { cnbRates.cnbRate("EUR", backfillDate) } returns fixing("24.900")
        coEvery { cnbRates.cnbRate("USD", backfillDate) } returns null
        coEvery { cnbRates.cnbRate("GBP", backfillDate) } returns null
        coEvery { glAccounts.findByCode("1995") } returns gl(eurCvId, "1995")
        coEvery { glAccounts.findByCode("5900") } returns gl(pnlId, "5900")
        coEvery { ledger.postJournal(any()) } returns stubEntry()

        val result = service.revalue(RevalueFxCommand(backfillDate))

        // 24.900, the fixing of the day being marked — not `date`'s 25.145.
        assertThat(result.movements).containsEntry("EUR", BigDecimal("24900000.00"))
        coVerify(exactly = 1) { cnbRates.cnbRate("EUR", backfillDate) }
        coVerify(exactly = 0) { cnbRates.cnbRate("EUR", date) }
    }

    @Test
    fun `an fx-service answer without a fixing date leaves the description and the age untouched`() =
        runBlocking<Unit> {
            coEvery { ledger.getTrialBalance(GetTrialBalanceQuery(date)) } returns TrialBalance(
                asOf = date,
                lines = listOf(position("1991", debit = "0", credit = "1000000")),
            )
            coEvery { cnbRates.cnbRate("EUR", date) } returns fixing("25.145", validFrom = null)
            coEvery { cnbRates.cnbRate("USD", date) } returns null
            coEvery { cnbRates.cnbRate("GBP", date) } returns null
            coEvery { glAccounts.findByCode("1995") } returns gl(eurCvId, "1995")
            coEvery { glAccounts.findByCode("5900") } returns gl(pnlId, "5900")
            val cmd = slot<PostJournalCommand>()
            coEvery { ledger.postJournal(capture(cmd)) } returns stubEntry()

            service.revalue(RevalueFxCommand(date))

            // "Age unknown" must never render as "just now" — the holder is seeded at
            // registration, so EUR reads as pod-age (0 under the fixed clock), and the
            // description gains no empty bracket.
            assertThat(cmd.captured.description).isEqualTo("Daily FX revaluation at ČNB fixing 2026-05-30")
            assertThat(age("EUR")).isEqualTo(0.0)
            // No fixing identity available, so the key degrades to exactly what it was before
            // #3921 — bare business day, corrections impossible. An honest inability, not a
            // fabricated identity that would make a correction LOOK possible and post garbage.
            assertThat(cmd.captured.idempotencyKey).isEqualTo("fx-reval-2026-05-30")
        }
}
