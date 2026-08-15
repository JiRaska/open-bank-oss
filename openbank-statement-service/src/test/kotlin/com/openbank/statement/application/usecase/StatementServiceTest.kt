// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.testing.audit.AuditEventTime
import com.openbank.statement.Fixtures
import com.openbank.statement.application.port.out.AccountInfoPort
import com.openbank.statement.application.port.out.BalancePort
import com.openbank.statement.application.port.out.BookedEntryPort
import com.openbank.statement.application.port.out.PocketAccountInfo
import com.openbank.statement.application.port.out.StatementOutboxMessage
import com.openbank.statement.application.port.out.StatementPeriodRepository
import com.openbank.statement.domain.model.BalanceAnchor
import com.openbank.statement.domain.model.CreditDebit
import com.openbank.statement.domain.model.PeriodCloseStatus
import com.openbank.statement.domain.model.StatementFormat
import com.openbank.statement.domain.model.StatementPeriod
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class StatementServiceTest {

    private val accountInfo = mockk<AccountInfoPort>()
    private val bookedEntries = mockk<BookedEntryPort>()
    private val balance = mockk<BalancePort>()
    private val periods = mockk<StatementPeriodRepository>(relaxed = false)

    private val from = LocalDate.parse("2026-01-01")
    private val to = LocalDate.parse("2026-01-31")

    private lateinit var service: StatementService
    private lateinit var restatement: StatementRestatementService

    @BeforeEach
    fun setUp() {
        service = StatementService(accountInfo, bookedEntries, balance, periods)
        service.clock = { Fixtures.CLOSED_AT }
        restatement = StatementRestatementService(accountInfo, bookedEntries, balance, periods)
        restatement.clock = { Fixtures.CLOSED_AT }
        every { accountInfo.pocketAccount(Fixtures.ACCOUNT_ID) } returns
            Uni.createFrom().item(PocketAccountInfo(Fixtures.ACCOUNT_ID, "CZ65", "Jan Novak", listOf("CZK")))
        every { bookedEntries.bookedEntries(any(), any(), any(), any()) } returns Uni.createFrom().item(
            listOf(
                Fixtures.entry(ref = "TX-1", amount = "100.00", cd = CreditDebit.CRDT),
                Fixtures.entry(ref = "TX-2", amount = "25.00", cd = CreditDebit.DBIT),
            ),
        )
        every { periods.priorClosing(any(), any(), any()) } returns Uni.createFrom().item(BigDecimal("1000.00"))
    }

    @Test
    fun `closing a month assigns the sequence, snapshots balances and emits the outbox event`() {
        every { periods.findByPeriod(any(), any(), any(), any()) } returns Uni.createFrom().nullItem()
        every { balance.closingBalance(Fixtures.ACCOUNT_ID, "CZK", to) } returns
            Uni.createFrom().item(BalanceAnchor(BigDecimal("1075.00"), "CZK", to))
        every { periods.nextLegalSequence(Fixtures.ACCOUNT_ID, "CZK") } returns Uni.createFrom().item(7L)
        every { periods.saveWithOutbox(any(), any()) } answers { Uni.createFrom().item(firstArg<StatementPeriod>()) }

        val result = service.closeMonth(Fixtures.ACCOUNT_ID, from, to).await().indefinitely()

        assertThat(result).hasSize(1)
        val p = result.first()
        assertThat(p.legalSequenceNumber).isEqualTo(7L)
        assertThat(p.openingBalance).isEqualByComparingTo("1000.00")
        assertThat(p.closingBalance).isEqualByComparingTo("1075.00")
        assertThat(p.entryCount).isEqualTo(2)
        assertThat(p.status).isEqualTo(PeriodCloseStatus.CLOSED)
        // Period + outbox event persist atomically in one call (no save()+append() split).
        verify(exactly = 1) {
            periods.saveWithOutbox(
                any<StatementPeriod>(),
                match<StatementOutboxMessage> { it.eventType == "account.statement.period.closed.v1" },
            )
        }
        verify(exactly = 0) { periods.save(any()) }
    }

    /**
     * #3914: red before the payload gained `occurredAt` — the close instant was in the payload only
     * as `closedAt`, a name `AuditConsumer` does not read, so the audit row for a statutory
     * period-close recorded the audit consumer's ingest clock as the close time.
     *
     * Asserts the value equals the period's own `closedAt`, not merely that a parseable instant is
     * present: a serialisation-time clock read would also be parseable, and would make a replayed
     * or re-emitted close claim a different business time each time.
     */
    @Test
    fun `the period-closed payload carries the close instant as the audit event time`() {
        every { periods.findByPeriod(any(), any(), any(), any()) } returns Uni.createFrom().nullItem()
        every { balance.closingBalance(Fixtures.ACCOUNT_ID, "CZK", to) } returns
            Uni.createFrom().item(BalanceAnchor(BigDecimal("1075.00"), "CZK", to))
        every { periods.nextLegalSequence(Fixtures.ACCOUNT_ID, "CZK") } returns Uni.createFrom().item(7L)
        val msg = slot<StatementOutboxMessage>()
        every { periods.saveWithOutbox(any(), capture(msg)) } answers
            { Uni.createFrom().item(firstArg<StatementPeriod>()) }

        val closed = service.closeMonth(Fixtures.ACCOUNT_ID, from, to).await().indefinitely().first()

        AuditEventTime.assertRecordedAsEventTime(msg.captured.payload, closed.closedAt)
    }

    /**
     * #3994 — red against `origin/main`, where the period-close payload carried no actor key and
     * the 86 statement audit rows (78 closed + 8 close_failed) stored NULL.
     *
     * A statutory period-close is scheduled, not pressed: nobody is the honest answer, and this
     * asserts the payload SAYS so with the exact canonical id rather than leaving a NULL that reads
     * identically to a human identity we lost. Parsed as JSON rather than substring-matched — the
     * payload is a hand-built `"""` template, so a `contains("SYSTEM")` would also match a value
     * that landed under some other key.
     */
    @Test
    fun `the period-closed payload names the scheduled close as its system origin`() {
        every { periods.findByPeriod(any(), any(), any(), any()) } returns Uni.createFrom().nullItem()
        every { balance.closingBalance(Fixtures.ACCOUNT_ID, "CZK", to) } returns
            Uni.createFrom().item(BalanceAnchor(BigDecimal("1075.00"), "CZK", to))
        every { periods.nextLegalSequence(Fixtures.ACCOUNT_ID, "CZK") } returns Uni.createFrom().item(7L)
        val msg = slot<StatementOutboxMessage>()
        every { periods.saveWithOutbox(any(), capture(msg)) } answers
            { Uni.createFrom().item(firstArg<StatementPeriod>()) }

        service.closeMonth(Fixtures.ACCOUNT_ID, from, to).await().indefinitely()

        val json = ObjectMapper().readTree(msg.captured.payload)
        assertThat(json.get("actorId").asText()).isEqualTo("system:statement-service:period-close")
        assertThat(json.get("actorType").asText()).isEqualTo("SYSTEM")
    }

    @Test
    fun `a re-run is idempotent - it returns the existing close without minting a new sequence`() {
        val existing = StatementPeriod(
            id = UUID.randomUUID(), accountId = Fixtures.ACCOUNT_ID, pocketCurrency = "CZK",
            periodFrom = from, periodTo = to, legalSequenceNumber = 3, electronicSequenceNumber = 3,
            openingBalance = BigDecimal("1000.00"), closingBalance = BigDecimal("1075.00"),
            entryCount = 2, closedAt = Fixtures.CLOSED_AT,
        )
        every { periods.findByPeriod(any(), any(), any(), any()) } returns Uni.createFrom().item(existing)

        val result = service.closeMonth(Fixtures.ACCOUNT_ID, from, to).await().indefinitely()

        assertThat(result).containsExactly(existing)
        verify(exactly = 0) { periods.nextLegalSequence(any(), any()) }
        verify(exactly = 0) { periods.saveWithOutbox(any(), any()) }
    }

    @Test
    fun `a closing balance that disagrees with balance-service fails the close - fail closed`() {
        every { periods.findByPeriod(any(), any(), any(), any()) } returns Uni.createFrom().nullItem()
        // computed = 1000 + (100 - 25) = 1075, but balance-service reports 9999 -> mismatch
        every { balance.closingBalance(Fixtures.ACCOUNT_ID, "CZK", to) } returns
            Uni.createFrom().item(BalanceAnchor(BigDecimal("9999.00"), "CZK", to))

        assertThatThrownBy { service.closeMonth(Fixtures.ACCOUNT_ID, from, to).await().indefinitely() }
            .isInstanceOf(ReconciliationException::class.java)

        verify(exactly = 0) { periods.saveWithOutbox(any(), any()) }
    }

    @Test
    fun `rendering a closed period replays it deterministically into camt-053`() {
        val period = StatementPeriod(
            id = UUID.randomUUID(), accountId = Fixtures.ACCOUNT_ID, pocketCurrency = "CZK",
            periodFrom = from, periodTo = to, legalSequenceNumber = 7, electronicSequenceNumber = 7,
            openingBalance = BigDecimal("1000.00"), closingBalance = BigDecimal("1075.00"),
            entryCount = 2, closedAt = Fixtures.CLOSED_AT,
        )
        every { periods.findBySequence(Fixtures.ACCOUNT_ID, "CZK", 7L) } returns Uni.createFrom().item(period)

        val rendered = service.render(Fixtures.ACCOUNT_ID, "CZK", 7L, StatementFormat.CAMT_053)
            .await().indefinitely()

        assertThat(rendered.format).isEqualTo(StatementFormat.CAMT_053)
        assertThat(rendered.contentType).isEqualTo("application/xml")
        assertThat(rendered.body).contains("<LglSeqNb>7</LglSeqNb>")
        assertThat(rendered.body).contains("<CreDtTm>2026-02-01T02:30:00Z</CreDtTm>")
    }

    /**
     * #3986 — **the falsifying test for this defect.** RED before the close-time snapshot existed.
     *
     * ADR-0035 §D/§F promise a closed period re-renders byte-identically. `statementModel` broke
     * that by rebuilding the model at render time from two LIVE projections, so this test mutates
     * exactly those two between the two renders:
     *
     *  - a late entry whose `bookingDate` (2026-01-20) falls inside the already-closed window —
     *    changes the entry lines AND makes the document stop reconciling, since the closing balance
     *    is a stored column and does not move with it;
     *  - a holder rename + IBAN change — rewrites the header of an already-issued legal document.
     *
     * Rendering once proves nothing here: the failure is *between* two renders, so the mutation has
     * to sit between them. All three formats are asserted — a snapshot that fixed camt but not
     * MT940/PDF would be a partial fix that a single-format assertion would call done.
     */
    @Test
    fun `two renders of a closed period are byte-identical across a late entry and a holder rename`() {
        every { periods.findByPeriod(any(), any(), any(), any()) } returns Uni.createFrom().nullItem()
        every { balance.closingBalance(Fixtures.ACCOUNT_ID, "CZK", to) } returns
            Uni.createFrom().item(BalanceAnchor(BigDecimal("1075.00"), "CZK", to))
        every { periods.nextLegalSequence(Fixtures.ACCOUNT_ID, "CZK") } returns Uni.createFrom().item(7L)
        val closed = slot<StatementPeriod>()
        every { periods.saveWithOutbox(capture(closed), any()) } answers {
            Uni.createFrom().item(firstArg<StatementPeriod>())
        }

        // Close through the REAL path, so the snapshot is minted by production code, not by the test.
        service.closeMonth(Fixtures.ACCOUNT_ID, from, to).await().indefinitely()
        every { periods.findBySequence(Fixtures.ACCOUNT_ID, "CZK", 7L) } returns
            Uni.createFrom().item(closed.captured)

        val before = StatementFormat.entries.associateWith {
            service.render(Fixtures.ACCOUNT_ID, "CZK", 7L, it).await().indefinitely().body
        }

        // --- the world moves under the closed period ---
        every { bookedEntries.bookedEntries(any(), any(), any(), any()) } returns Uni.createFrom().item(
            listOf(
                Fixtures.entry(ref = "TX-1", amount = "100.00", cd = CreditDebit.CRDT),
                Fixtures.entry(ref = "TX-2", amount = "25.00", cd = CreditDebit.DBIT),
                Fixtures.entry(ref = "TX-3-LATE", amount = "500.00", cd = CreditDebit.DBIT, booking = "2026-01-20"),
            ),
        )
        every { accountInfo.pocketAccount(Fixtures.ACCOUNT_ID) } returns
            Uni.createFrom().item(PocketAccountInfo(Fixtures.ACCOUNT_ID, "CZ99", "Jana Novakova", listOf("CZK")))

        val after = StatementFormat.entries.associateWith {
            service.render(Fixtures.ACCOUNT_ID, "CZK", 7L, it).await().indefinitely().body
        }

        assertThat(after).isEqualTo(before)
        // ...and the reason it holds, pinned so a future refactor cannot pass this by accident:
        // exactly ONE read of each live port in the whole test — the one the CLOSE makes. The six
        // renders above add none. (`exactly = 0` would be wrong: the close must read them, that is
        // where the frozen values come from.)
        verify(exactly = 1) { bookedEntries.bookedEntries(Fixtures.ACCOUNT_ID, "CZK", from, to) }
        verify(exactly = 1) { accountInfo.pocketAccount(Fixtures.ACCOUNT_ID) }
    }

    /**
     * #3986, the deliberate other half: a period closed BEFORE the snapshot existed has none, and
     * still replays the live projections. Pinned so the fallback cannot be quietly deleted (it is
     * the only way those rows render at all) and cannot be quietly widened into a backfill —
     * inventing a snapshot from today's data would freeze drift and stamp it as the issued document.
     */
    @Test
    fun `a period closed before the snapshot existed still replays live data`() {
        val legacy = StatementPeriod(
            id = UUID.randomUUID(), accountId = Fixtures.ACCOUNT_ID, pocketCurrency = "CZK",
            periodFrom = from, periodTo = to, legalSequenceNumber = 7, electronicSequenceNumber = 7,
            openingBalance = BigDecimal("1000.00"), closingBalance = BigDecimal("1075.00"),
            entryCount = 2, closedAt = Fixtures.CLOSED_AT, snapshot = null,
        )
        every { periods.findBySequence(Fixtures.ACCOUNT_ID, "CZK", 7L) } returns Uni.createFrom().item(legacy)

        val rendered = service.render(Fixtures.ACCOUNT_ID, "CZK", 7L, StatementFormat.MT940)
            .await().indefinitely()

        assertThat(rendered.body).contains("TX-1")
        verify(exactly = 1) { bookedEntries.bookedEntries(Fixtures.ACCOUNT_ID, "CZK", from, to) }
        verify(exactly = 1) { accountInfo.pocketAccount(Fixtures.ACCOUNT_ID) }
    }

    /**
     * #3986 — a correction must not be born with the defect it corrects: the superseding page
     * freezes its OWN render inputs, or the restated statement would drift exactly as the original
     * one did.
     */
    @Test
    fun `a restatement freezes the superseding page's own snapshot`() {
        val standing = StatementPeriod(
            id = UUID.randomUUID(), accountId = Fixtures.ACCOUNT_ID, pocketCurrency = "CZK",
            periodFrom = from, periodTo = to, legalSequenceNumber = 7, electronicSequenceNumber = 7,
            openingBalance = BigDecimal("1000.00"), closingBalance = BigDecimal("9999.00"),
            entryCount = 1, closedAt = Fixtures.CLOSED_AT,
        )
        every { periods.findByPeriod(Fixtures.ACCOUNT_ID, "CZK", from, to) } returns
            Uni.createFrom().item(standing)
        every { balance.closingBalance(Fixtures.ACCOUNT_ID, "CZK", to) } returns
            Uni.createFrom().item(BalanceAnchor(BigDecimal("1075.00"), "CZK", to))
        every { periods.nextLegalSequence(Fixtures.ACCOUNT_ID, "CZK") } returns Uni.createFrom().item(8L)
        every { periods.supersedeAndReplace(any(), any(), any()) } answers {
            Uni.createFrom().item(secondArg<StatementPeriod>())
        }

        val replacement = restatement.restatePocketPeriod(Fixtures.ACCOUNT_ID, "CZK", from, to)
            .await().indefinitely()

        assertThat(replacement.legalSequenceNumber).isEqualTo(8L)
        val frozen = replacement.snapshot
        assertThat(frozen).isNotNull
        assertThat(frozen!!.holderName).isEqualTo("Jan Novak")
        assertThat(frozen.entries.map { it.entryRef }).containsExactly("TX-1", "TX-2")
    }

    @Test
    fun `rendering a missing sequence fails with not-found`() {
        every { periods.findBySequence(any(), any(), any()) } returns Uni.createFrom().nullItem()

        assertThatThrownBy {
            service.render(Fixtures.ACCOUNT_ID, "CZK", 99L, StatementFormat.MT940).await().indefinitely()
        }.isInstanceOf(StatementNotFoundException::class.java)
    }

    @Test
    fun `summary returns the same closed period as render, but as the canonical model - no renderer`() {
        val period = StatementPeriod(
            id = UUID.randomUUID(), accountId = Fixtures.ACCOUNT_ID, pocketCurrency = "CZK",
            periodFrom = from, periodTo = to, legalSequenceNumber = 7, electronicSequenceNumber = 7,
            openingBalance = BigDecimal("1000.00"), closingBalance = BigDecimal("1075.00"),
            entryCount = 2, closedAt = Fixtures.CLOSED_AT,
        )
        every { periods.findBySequence(Fixtures.ACCOUNT_ID, "CZK", 7L) } returns Uni.createFrom().item(period)

        val model = service.summary(Fixtures.ACCOUNT_ID, "CZK", 7L).await().indefinitely()

        assertThat(model.legalSequenceNumber).isEqualTo(7L)
        assertThat(model.openingBalance.amount).isEqualByComparingTo("1000.00")
        assertThat(model.closingBalance.amount).isEqualByComparingTo("1075.00")
        assertThat(model.entries).hasSize(2)
        assertThat(model.iban).isEqualTo("CZ65")
    }

    @Test
    fun `summary of a missing sequence fails with not-found, same as render`() {
        every { periods.findBySequence(any(), any(), any()) } returns Uni.createFrom().nullItem()

        assertThatThrownBy {
            service.summary(Fixtures.ACCOUNT_ID, "CZK", 99L).await().indefinitely()
        }.isInstanceOf(StatementNotFoundException::class.java)
    }

    @Test
    fun `an ad-hoc export carries no legal sequence`() {
        val rendered = service.export(Fixtures.ACCOUNT_ID, "CZK", from, to, StatementFormat.PDF)
            .await().indefinitely()

        // Non-sequenced informational export -> legal sequence 0 on the PDF.
        assertThat(rendered.body).contains("Statement no. (legal): 0")
        verify(exactly = 0) { periods.saveWithOutbox(any(), any()) }
    }

    // ---- Restatement of a closed period (ADR-0035 §D, issue #1302 item 5) -------------------
    //
    // Before this, `PeriodCloseStatus.SUPERSEDED` had ZERO write sites fleet-wide: a period closed
    // on wrong data was immutable, and `closePocketMonth` silently returned the stale record.

    private fun standingClose(
        seq: Long = 3,
        opening: String = "1000.00",
        closing: String = "1075.00",
        entryCount: Int = 2,
    ) = StatementPeriod(
        id = UUID.randomUUID(), accountId = Fixtures.ACCOUNT_ID, pocketCurrency = "CZK",
        periodFrom = from, periodTo = to, legalSequenceNumber = seq, electronicSequenceNumber = seq,
        openingBalance = BigDecimal(opening), closingBalance = BigDecimal(closing),
        entryCount = entryCount, closedAt = Fixtures.CLOSED_AT,
    )

    @Test
    fun `restating a corrected period issues a new sequenced close referencing the superseded one`() {
        // The standing close was built before TX-2 (the 25.00 fee) was booked: it says 1100.00 with
        // one entry. The corrected truth is 1000 + 100 - 25 = 1075.00 over two entries.
        val standing = standingClose(seq = 3, closing = "1100.00", entryCount = 1)
        every { periods.findByPeriod(any(), any(), any(), any()) } returns Uni.createFrom().item(standing)
        every { balance.closingBalance(Fixtures.ACCOUNT_ID, "CZK", to) } returns
            Uni.createFrom().item(BalanceAnchor(BigDecimal("1075.00"), "CZK", to))
        every { periods.nextLegalSequence(Fixtures.ACCOUNT_ID, "CZK") } returns Uni.createFrom().item(4L)
        every { periods.supersedeAndReplace(any(), any(), any()) } answers {
            Uni.createFrom().item(secondArg<StatementPeriod>())
        }

        val restated = restatement.restatePocketPeriod(Fixtures.ACCOUNT_ID, "CZK", from, to).await().indefinitely()

        // The FIGURES are the assertion, not merely that a status changed.
        assertThat(restated.closingBalance).isEqualByComparingTo("1075.00")
        assertThat(restated.openingBalance).isEqualByComparingTo("1000.00")
        assertThat(restated.entryCount).isEqualTo(2)
        assertThat(restated.legalSequenceNumber).isEqualTo(4L)
        assertThat(restated.electronicSequenceNumber).isEqualTo(4L)
        assertThat(restated.supersedesSequence).isEqualTo(3L)
        assertThat(restated.status).isEqualTo(PeriodCloseStatus.CLOSED)
        assertThat(restated.id).isNotEqualTo(standing.id)

        // The prior record is superseded in the SAME call (one transaction), and the correction is
        // announced on its own event type, not silently as another `period.closed`.
        verify(exactly = 1) {
            periods.supersedeAndReplace(
                standing.id,
                any(),
                match<StatementOutboxMessage> {
                    it.eventType == "account.statement.period.restated.v1" &&
                        it.payload.contains("\"supersedesSequence\":3") &&
                        it.payload.contains("\"closingBalance\":1075.00") &&
                        it.payload.contains("\"supersededClosingBalance\":1100.00")
                },
            )
        }
        // Never an in-place edit, and never a plain close.
        verify(exactly = 0) { periods.save(any()) }
        verify(exactly = 0) { periods.saveWithOutbox(any(), any()) }
    }

    @Test
    fun `restating a period whose figures are unchanged burns no legal sequence`() {
        val standing = standingClose(seq = 3, closing = "1075.00", entryCount = 2)
        every { periods.findByPeriod(any(), any(), any(), any()) } returns Uni.createFrom().item(standing)
        every { balance.closingBalance(Fixtures.ACCOUNT_ID, "CZK", to) } returns
            Uni.createFrom().item(BalanceAnchor(BigDecimal("1075.00"), "CZK", to))

        val result = restatement.restatePocketPeriod(Fixtures.ACCOUNT_ID, "CZK", from, to).await().indefinitely()

        assertThat(result).isEqualTo(standing)
        verify(exactly = 0) { periods.nextLegalSequence(any(), any()) }
        verify(exactly = 0) { periods.supersedeAndReplace(any(), any(), any()) }
    }

    @Test
    fun `a restatement that cannot itself reconcile leaves the standing close untouched - fail closed`() {
        val standing = standingClose(seq = 3, closing = "1100.00", entryCount = 1)
        every { periods.findByPeriod(any(), any(), any(), any()) } returns Uni.createFrom().item(standing)
        // computed = 1000 + (100 - 25) = 1075, balance-service says 9999 -> mismatch
        every { balance.closingBalance(Fixtures.ACCOUNT_ID, "CZK", to) } returns
            Uni.createFrom().item(BalanceAnchor(BigDecimal("9999.00"), "CZK", to))

        assertThatThrownBy {
            restatement.restatePocketPeriod(Fixtures.ACCOUNT_ID, "CZK", from, to).await().indefinitely()
        }.isInstanceOf(ReconciliationException::class.java)

        verify(exactly = 0) { periods.supersedeAndReplace(any(), any(), any()) }
    }

    @Test
    fun `restating a window that was never closed is rejected, not turned into a first close`() {
        every { periods.findByPeriod(any(), any(), any(), any()) } returns Uni.createFrom().nullItem()

        assertThatThrownBy {
            restatement.restatePocketPeriod(Fixtures.ACCOUNT_ID, "CZK", from, to).await().indefinitely()
        }.isInstanceOf(NoClosedPeriodToRestateException::class.java)

        verify(exactly = 0) { periods.nextLegalSequence(any(), any()) }
        verify(exactly = 0) { periods.supersedeAndReplace(any(), any(), any()) }
    }
}
