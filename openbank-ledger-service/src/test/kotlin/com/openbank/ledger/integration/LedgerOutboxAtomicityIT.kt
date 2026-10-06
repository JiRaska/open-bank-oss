// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.integration

import com.openbank.ledger.application.port.`in`.JournalLineRequest
import com.openbank.ledger.application.port.`in`.LedgerUseCase
import com.openbank.ledger.application.port.`in`.PostJournalCommand
import com.openbank.ledger.domain.model.JournalSide
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Issue #8353 — proves that `PanacheJournalRepository.save` commits the `journal_entries` row and
 * every `ledger_outbox` row it announces in **one** database transaction.
 *
 * ### Why presence is not the property
 *
 * The sibling [LedgerOutboxProjectionIT] posts a journal and counts the outbox rows that landed.
 * That is necessary and not sufficient: an implementation that persisted the journal in one
 * transaction and its outbox rows in a second would satisfy every count while having lost the
 * property — a crash between the two leaves a posted journal nobody downstream ever hears about,
 * or an announced one that does not exist.
 *
 * ### What makes it falsifiable
 *
 * Postgres stamps every row version with `xmin`, the id of the transaction that wrote it. Rows
 * written by one transaction carry the same `xmin`; rows written by two transactions cannot. Moving
 * `persistOutbox` out of `save`'s `Panache.withTransaction` turns this test red where a count stays
 * green.
 *
 * Nothing re-writes the rows between the post and the read: `%test` switches the scheduler off for
 * this service, so the outbox dispatcher — whose claim UPDATE would stamp a new `xmin` — never
 * ticks. The arrangement test below fails if that stops being true.
 *
 * Reactive calls run on a Vert.x duplicated context, as in [LedgerOutboxProjectionIT]. Each test
 * uses a block body so JUnit5 cannot silently skip an expression-bodied one.
 */
@QuarkusTest
class LedgerOutboxAtomicityIT {

    @Inject
    lateinit var ledger: LedgerUseCase

    // Deterministic posting accounts seeded by V3__ledger_governance.sql.
    private val glAssetId = UUID.fromString("a0000000-0000-0000-0000-000000000001")
    private val glDepositControlId = UUID.fromString("a0000000-0000-0000-0000-000000000002")

    private fun <T> onVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    @Test
    fun `a posted journal and all of its outbox rows are written by one transaction`() {
        val first = postDeposit()
        val second = postDeposit()

        val journalXmin = xmin("journal_entries", "id", first.journalId)
        val postedXmin = outboxXmin(first.journalId, "JournalPosted")
        val bookedXmin = outboxXmin(first.subAccountId, "AccountBookedChanged")

        assertThat(journalXmin).describedAs("journal %s must exist", first.journalId).hasSize(1)
        assertThat(postedXmin).describedAs("exactly one JournalPosted row").hasSize(1)
        assertThat(bookedXmin).describedAs("exactly one AccountBookedChanged row").hasSize(1)

        assertThat(postedXmin.single())
            .describedAs(
                "the journal row and its JournalPosted outbox row must carry the SAME Postgres xmin — " +
                    "different values mean two transactions wrote them, so one can commit without the other",
            )
            .isEqualTo(journalXmin.single())
        assertThat(bookedXmin.single())
            .describedAs("the AccountBookedChanged projection row rides the same transaction (ADR-0039)")
            .isEqualTo(journalXmin.single())

        // Known-different control: two postings are two transactions. The identical comparison must
        // therefore FAIL across them — otherwise the matches above would match everything.
        assertThat(xmin("journal_entries", "id", second.journalId).single())
            .describedAs("control: two separate postings cannot share a writing transaction")
            .isNotEqualTo(journalXmin.single())
    }

    /**
     * Guards the assertions above against reading success from an empty set, and against the
     * dispatcher having quietly started: a never-written id yields nothing, and a freshly written
     * outbox row is still exactly as it was inserted.
     */
    @Test
    fun `the probe sees nothing for an unknown journal and the outbox row is still unclaimed`() {
        assertThat(xmin("journal_entries", "id", UUID.randomUUID())).isEmpty()
        assertThat(outboxXmin(UUID.randomUUID(), "JournalPosted")).isEmpty()

        val posted = postDeposit()
        val attempts = onVertxContext {
            Panache.withSession {
                Panache.getSession().flatMap { session ->
                    session.createNativeQuery(
                        "select attempt_count from ledger_outbox where aggregate_id = :agg and event_type = :et",
                        java.lang.Integer::class.java,
                    )
                        .setParameter("agg", posted.journalId)
                        .setParameter("et", "JournalPosted")
                        .resultList
                }
            }.awaitSuspending()
        }
        assertThat(attempts.map { it.toInt() })
            .describedAs("an untouched outbox row: the dispatcher must not run during this class")
            .containsExactly(0)
    }

    private data class Posted(val journalId: UUID, val subAccountId: UUID)

    private fun postDeposit(): Posted {
        val subAccount = UUID.randomUUID()
        val command = PostJournalCommand(
            idempotencyKey = UUID.randomUUID().toString(),
            transactionId = UUID.randomUUID(),
            entryDate = LocalDate.now(),
            valueDate = LocalDate.now(),
            description = "Outbox atomicity (IT)",
            lines = listOf(
                JournalLineRequest(
                    glAccountId = glAssetId,
                    side = JournalSide.DEBIT,
                    amount = BigDecimal("25.00"),
                    currencyCode = "CZK",
                    fxRate = null,
                    baseAmount = BigDecimal("25.00"),
                    baseCurrencyCode = "CZK",
                ),
                JournalLineRequest(
                    glAccountId = glDepositControlId,
                    side = JournalSide.CREDIT,
                    amount = BigDecimal("25.00"),
                    currencyCode = "CZK",
                    fxRate = null,
                    baseAmount = BigDecimal("25.00"),
                    baseCurrencyCode = "CZK",
                    subAccountId = subAccount,
                ),
            ),
            postedBy = UUID.randomUUID(),
        )
        val entry = onVertxContext { ledger.postJournal(command) }
        return Posted(entry.id, subAccount)
    }

    private fun outboxXmin(aggregateId: UUID, eventType: String): List<String> = onVertxContext {
        Panache.withSession {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery(
                    "select cast(xmin as text) from ledger_outbox where aggregate_id = :agg and event_type = :et",
                    String::class.java,
                )
                    .setParameter("agg", aggregateId)
                    .setParameter("et", eventType)
                    .resultList
            }
        }.awaitSuspending()
    }

    /** `table` and `column` are compile-time literals at every call site, never request input. */
    private fun xmin(table: String, column: String, id: UUID): List<String> = onVertxContext {
        Panache.withSession {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery(
                    "select cast(xmin as text) from $table where $column = :id",
                    String::class.java,
                )
                    .setParameter("id", id)
                    .resultList
            }
        }.awaitSuspending()
    }
}
