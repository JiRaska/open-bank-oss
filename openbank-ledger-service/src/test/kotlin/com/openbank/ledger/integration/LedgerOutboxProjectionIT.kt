// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.integration

import com.openbank.ledger.application.port.`in`.JournalLineRequest
import com.openbank.ledger.application.port.`in`.LedgerUseCase
import com.openbank.ledger.application.port.`in`.PostJournalCommand
import com.openbank.ledger.domain.model.JournalSide
import com.openbank.libs.persistence.outbox.OutboxMessage
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
 * ADR-0039: a single posting must write its `JournalPosted` row AND one `AccountBookedChanged` row per
 * affected (customer account, currency) into the transactional outbox in the SAME database transaction
 * as the journal itself — the N+1 outbox rows and the journal commit or roll back together.
 *
 * Exercises the real reactive persistence path (LedgerService → PanacheJournalRepository →
 * `Panache.withTransaction`) against the dedicated ledger IT database, then counts the outbox rows that
 * landed. Status-independent counts, so the 5s outbox dispatcher draining rows to SENT does not race
 * the assertions.
 *
 * Reactive suspend calls MUST run on a Vert.x duplicated context (see [JournalPartitionMaintainerIT]);
 * [onVertxContext] bridges them. Each test uses a block body — a `fun x() = expr` expression body
 * inferring a non-`Unit` type makes JUnit5 silently SKIP it.
 */
@QuarkusTest
class LedgerOutboxProjectionIT {

    @Inject
    lateinit var ledger: LedgerUseCase

    // Deterministic posting accounts seeded by V3__ledger_governance.sql.
    private val glAssetId = UUID.fromString("a0000000-0000-0000-0000-000000000001") // 1100 ASSET

    // 2100 LIABILITY, deposit control
    private val glDepositControlId = UUID.fromString("a0000000-0000-0000-0000-000000000002")

    private fun <T> onVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    private fun outboxCount(aggregateId: UUID, eventType: String): Long = onVertxContext {
        Panache.withSession {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery(
                    "select count(*) from ledger_outbox where aggregate_id = :agg and event_type = :et",
                    java.lang.Long::class.java,
                )
                    .setParameter("agg", aggregateId)
                    .setParameter("et", eventType)
                    .singleResult
            }
        }.awaitSuspending()
    }.toLong()

    @Test
    fun `a deposit posting writes JournalPosted plus one AccountBookedChanged in the same transaction`() {
        val subAccount = UUID.randomUUID()
        val operator = UUID.randomUUID()
        val command = PostJournalCommand(
            idempotencyKey = UUID.randomUUID().toString(),
            transactionId = UUID.randomUUID(),
            entryDate = LocalDate.now(),
            valueDate = LocalDate.now(),
            description = "Customer deposit (IT)",
            lines = listOf(
                JournalLineRequest(
                    glAccountId = glAssetId,
                    side = JournalSide.DEBIT,
                    amount = BigDecimal("1000.00"),
                    currencyCode = "CZK",
                    fxRate = null,
                    baseAmount = BigDecimal("1000.00"),
                    baseCurrencyCode = "CZK",
                ),
                JournalLineRequest(
                    glAccountId = glDepositControlId,
                    side = JournalSide.CREDIT,
                    amount = BigDecimal("1000.00"),
                    currencyCode = "CZK",
                    fxRate = null,
                    baseAmount = BigDecimal("1000.00"),
                    baseCurrencyCode = "CZK",
                    subAccountId = subAccount,
                ),
            ),
            postedBy = operator,
        )

        val entry = onVertxContext { ledger.postJournal(command) }

        // N+1 outbox rows: JournalPosted keyed by the journal id, AccountBookedChanged keyed by the
        // customer account id — both committed atomically with the journal.
        assertThat(outboxCount(entry.id, "JournalPosted")).isEqualTo(1L)
        assertThat(outboxCount(subAccount, "AccountBookedChanged")).isEqualTo(1L)
    }

    @Test
    fun `a posting with two deposit-control accounts writes N+1 outbox rows`() {
        val subA = UUID.randomUUID()
        val subB = UUID.randomUUID()
        val command = PostJournalCommand(
            idempotencyKey = UUID.randomUUID().toString(),
            transactionId = UUID.randomUUID(),
            entryDate = LocalDate.now(),
            valueDate = LocalDate.now(),
            description = "Split deposit (IT)",
            lines = listOf(
                JournalLineRequest(
                    glAccountId = glAssetId,
                    side = JournalSide.DEBIT,
                    amount = BigDecimal("1000.00"),
                    currencyCode = "CZK",
                    fxRate = null,
                    baseAmount = BigDecimal("1000.00"),
                    baseCurrencyCode = "CZK",
                ),
                JournalLineRequest(
                    glAccountId = glDepositControlId,
                    side = JournalSide.CREDIT,
                    amount = BigDecimal("600.00"),
                    currencyCode = "CZK",
                    fxRate = null,
                    baseAmount = BigDecimal("600.00"),
                    baseCurrencyCode = "CZK",
                    subAccountId = subA,
                ),
                JournalLineRequest(
                    glAccountId = glDepositControlId,
                    side = JournalSide.CREDIT,
                    amount = BigDecimal("400.00"),
                    currencyCode = "CZK",
                    fxRate = null,
                    baseAmount = BigDecimal("400.00"),
                    baseCurrencyCode = "CZK",
                    subAccountId = subB,
                ),
            ),
            postedBy = UUID.randomUUID(),
        )

        val entry = onVertxContext { ledger.postJournal(command) }

        // N = 2 deposit-control accounts → 1 JournalPosted + 2 AccountBookedChanged = 3 rows.
        assertThat(outboxCount(entry.id, "JournalPosted")).isEqualTo(1L)
        assertThat(outboxCount(subA, "AccountBookedChanged")).isEqualTo(1L)
        assertThat(outboxCount(subB, "AccountBookedChanged")).isEqualTo(1L)
    }

    /**
     * #1201 proposed fix 3: `PostJournalCommand.additionalOutboxMessages` — a caller's own event
     * (e.g. FX revaluation's `FxRevaluedEvent`, via `FxRevaluationService.revalue`) must commit in
     * the SAME transaction as the journal it accompanies, so a crash after this call cannot leave
     * the journal posted with the caller's event lost. `FxRevaluationServiceTest` proves the right
     * message gets BUILT (a mockk-based unit test, no real DB); this proves the generic
     * additionalOutboxMessages mechanism actually PERSISTS atomically against a real Postgres —
     * against a fake real production code would still commit the journal even if the outbox write
     * silently failed, and no unit test with a mocked repository can catch that.
     */
    @Test
    fun `additionalOutboxMessages commit in the same transaction as the journal they accompany`() {
        val command = PostJournalCommand(
            idempotencyKey = UUID.randomUUID().toString(),
            transactionId = UUID.randomUUID(),
            entryDate = LocalDate.now(),
            valueDate = LocalDate.now(),
            description = "Posting with an extra outboxed event (IT)",
            lines = listOf(
                JournalLineRequest(
                    glAccountId = glAssetId,
                    side = JournalSide.DEBIT,
                    amount = BigDecimal("500.00"),
                    currencyCode = "CZK",
                    fxRate = null,
                    baseAmount = BigDecimal("500.00"),
                    baseCurrencyCode = "CZK",
                ),
                JournalLineRequest(
                    glAccountId = glDepositControlId,
                    side = JournalSide.CREDIT,
                    amount = BigDecimal("500.00"),
                    currencyCode = "CZK",
                    fxRate = null,
                    baseAmount = BigDecimal("500.00"),
                    baseCurrencyCode = "CZK",
                    subAccountId = UUID.randomUUID(),
                ),
            ),
            postedBy = UUID.randomUUID(),
            additionalOutboxMessages = { entry ->
                listOf(OutboxMessage(aggregateId = entry.id, eventType = "TestExtraEvent", payload = "{}"))
            },
        )

        val entry = onVertxContext { ledger.postJournal(command) }

        assertThat(outboxCount(entry.id, "JournalPosted")).isEqualTo(1L)
        assertThat(outboxCount(entry.id, "TestExtraEvent")).isEqualTo(1L)
    }
}
