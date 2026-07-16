// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.interest.integration

import com.openbank.interest.application.port.out.CapitalizationPosting
import com.openbank.interest.application.port.out.LedgerPostingPort
import com.openbank.interest.infrastructure.client.CapitalizationJournalFactory
import com.openbank.interest.infrastructure.client.InterestLedgerConfig
import com.openbank.interest.infrastructure.client.JournalLineRequest
import com.openbank.interest.infrastructure.client.PostJournalRequest
import com.openbank.libs.domain.money.Money
import io.quarkus.test.Mock
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * A booked journal, as ledger-service would hold it: every line already re-wrapped as [Money], which
 * is the form the amounts must survive to be booked at all.
 */
data class BookedJournal(val idempotencyKey: String, val transactionId: UUID, val lines: List<BookedLine>) {
    fun debits(): List<BookedLine> = lines.filter { it.side == "DEBIT" }
    fun credits(): List<BookedLine> = lines.filter { it.side == "CREDIT" }
}

data class BookedLine(val glAccountId: UUID, val side: String, val amount: Money, val subAccountId: UUID?)

/**
 * The ledger boundary, in-process — the thing every existing test in this module stops one step
 * short of, which is why a defect that broke **100 %** of money-bearing capitalizations shipped
 * green.
 *
 * ### What is real here, and what is not
 *
 * Real, because the defect lived in exactly these two places:
 * - the production [CapitalizationJournalFactory] builds the request (no re-modelling of the split);
 * - **[Money] is the real production class** from `openbank-libs-domain`, and [book] constructs every
 *   line through `Money.of(amount, currencyCode)` / `Money.of(baseAmount, baseCurrencyCode)` — the
 *   exact two calls `LedgerService.postJournal` makes (`LedgerService.kt:95,97`). `Money`'s init
 *   requires `amount.scale() <= currency.defaultFractionDigits`; a scale-4 CZK amount throws there
 *   and `CommonExceptionMappers` turns it into an HTTP 400. This is NOT a hand-rolled restatement of
 *   the ledger's rule — `CapitalizationJournalFactoryTest.assertBalanced` used to be one, and it
 *   omitted precisely this rule. It is the same object doing the same check.
 * - [book] also reproduces `LedgerService.postJournal`'s **idempotent replay verbatim**: a repeated
 *   key returns the already-booked entry *before any validation and without comparing amounts*
 *   (`LedgerService.kt:78`). That indifference to the amount is what makes finding 2's divergence
 *   silent, so a stand-in that "helpfully" compared amounts would test a ledger that does not exist.
 *
 * Not real: this is not the `LedgerService` CDI bean over HTTP. `openbank-interest-service` cannot
 * put `openbank-ledger-service` on its classpath — both ship `db/migration/V1__*.sql`, so Flyway
 * would refuse to boot ("more than one migration with version 1"), and Quarkus would bean-scan the
 * ledger's entities into this application. A cross-service test needs a harness that runs both
 * containers, which does not exist in this repo. Consequently the ledger's GL-account existence and
 * per-account currency checks (its 422s) are NOT exercised here; they are ledger-service's own tests.
 * The scale invariant, the balance rule, the line count and the replay semantics — the four things
 * this PR can break — are.
 */
@Mock
@ApplicationScoped
class LedgerBoundary : LedgerPostingPort {

    private val journals = LinkedHashMap<String, BookedJournal>()

    /** Set to fail the post AFTER the journal is booked — the crash window this PR argues about. */
    private val crashAfterBooking = AtomicReference<String?>(null)

    /** Set to fail the post BEFORE anything is booked — a plain ledger outage. */
    private val failBeforeBooking = AtomicReference<String?>(null)

    /** Every journal booked, in order. More than one per period is a double credit. */
    fun booked(): List<BookedJournal> = journals.values.toList()

    fun journalFor(key: String): BookedJournal? = journals[key]

    /**
     * Makes the next post book its journal and then fail, exactly as a pod dying between the ledger
     * commit and `saveWithOutbox` looks from interest-service: the money moved, the local write set
     * did not.
     */
    fun crashAfterNextBooking(reason: String) = crashAfterBooking.set(reason)

    /** Fails the next post without booking anything — the ledger is simply unreachable. */
    fun failNextPost(reason: String) = failBeforeBooking.set(reason)

    fun reset() {
        journals.clear()
        crashAfterBooking.set(null)
        failBeforeBooking.set(null)
    }

    override fun post(posting: CapitalizationPosting): Uni<Unit> {
        failBeforeBooking.getAndSet(null)?.let { return Uni.createFrom().failure(IllegalStateException(it)) }
        return runCatching {
            book(CapitalizationJournalFactory.buildRequest(posting, TestInterestLedgerConfig))
        }.fold(
            onSuccess = {
                val crash = crashAfterBooking.getAndSet(null)
                if (crash != null) {
                    Uni.createFrom().failure(IllegalStateException(crash))
                } else {
                    Uni.createFrom().item(Unit)
                }
            },
            onFailure = { Uni.createFrom().failure(it) },
        )
    }

    /**
     * `LedgerService.postJournal`, in the two respects that can break here.
     *
     * Replay first (before any validation, no amount comparison), then `Money`-construct every line,
     * then the `JournalEntry` init rules: at least two lines, `amount > 0` per line, and debits ==
     * credits per currency.
     */
    private fun book(request: PostJournalRequest): BookedJournal {
        journals[request.idempotencyKey]?.let { return it }

        val lines = request.lines.map { l: JournalLineRequest ->
            // The invariant that 400'd every capitalization. Real Money, real init, real throw.
            val amount = Money.of(l.amount, l.currencyCode)
            Money.of(l.baseAmount, l.baseCurrencyCode)
            BookedLine(l.glAccountId, l.side, amount, l.subAccountId)
        }
        require(lines.size >= 2) { "A journal entry requires at least 2 lines, got ${lines.size}" }
        require(lines.all { it.amount.isPositive() }) { "Every journal line requires amount > 0" }
        lines.map { it.amount.currency }.distinct().forEach { ccy ->
            val debits = lines.filter { it.side == "DEBIT" && it.amount.currency == ccy }
                .fold(Money.zero(ccy.code)) { acc, l -> acc + l.amount }
            val credits = lines.filter { it.side == "CREDIT" && it.amount.currency == ccy }
                .fold(Money.zero(ccy.code)) { acc, l -> acc + l.amount }
            require(debits.amount.compareTo(credits.amount) == 0) {
                "Journal entry does not balance in $ccy: debits=$debits credits=$credits"
            }
        }
        val journal = BookedJournal(request.idempotencyKey, request.transactionId, lines)
        journals[request.idempotencyKey] = journal
        return journal
    }
}

/** The seeded defaults from `V17__interest_capitalization_accounts.sql`. */
object TestInterestLedgerConfig : InterestLedgerConfig {
    val depositControlCzk: UUID = UUID.fromString("a0000000-0000-0000-0000-000000000002")
    val interestExpenseCzk: UUID = UUID.fromString("a0000000-0000-0000-0000-000000004010")
    val withholdingTaxPayableCzk: UUID = UUID.fromString("a0000000-0000-0000-0000-000000002200")

    override fun systemActorId(): UUID = UUID.fromString("00000000-0000-0000-0000-0000000000cc")
    override fun gl(): InterestLedgerConfig.Gl = TestGl

    object TestGl : InterestLedgerConfig.Gl {
        override fun interestExpenseCzk(): UUID = UUID.fromString("a0000000-0000-0000-0000-000000004010")
        override fun interestExpenseEur(): UUID = UUID.fromString("a0000000-0000-0000-0000-000000004011")
        override fun interestExpenseUsd(): UUID = UUID.fromString("a0000000-0000-0000-0000-000000004012")
        override fun interestExpenseGbp(): UUID = UUID.fromString("a0000000-0000-0000-0000-000000004013")
        override fun withholdingTaxPayable(): UUID = UUID.fromString("a0000000-0000-0000-0000-000000002200")
    }
}
