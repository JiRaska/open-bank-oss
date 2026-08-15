// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.integration

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.statement.domain.model.BalanceAnchor
import com.openbank.statement.domain.model.CreditDebit
import com.openbank.statement.domain.model.PeriodCloseStatus
import com.openbank.statement.domain.model.StatementEntry
import com.openbank.statement.domain.model.StatementFormat
import com.openbank.statement.domain.model.StatementModel
import com.openbank.statement.domain.model.StatementPeriod
import com.openbank.statement.domain.model.StatementSnapshot
import com.openbank.statement.domain.render.StatementRenderer
import com.openbank.statement.infrastructure.persistence.repository.StatementPeriodRepositoryImpl
import com.openbank.statement.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Real-DB coverage for the close-time render snapshot (ADR-0035 §D/§F, issue #3986).
 *
 * The unit test in `StatementServiceTest` proves the *service* stops re-querying. It cannot prove
 * the half that lives below the port: the snapshot has to survive a round trip through
 * `V7__statement_period_model_snapshot.sql` **with its `BigDecimal` scale and its entry ORDER
 * intact**, because a renderer prints both. A mocked repository hands back the very object it was
 * given, so it agrees with any serialisation — including one that turns `100.00` into `100.0` or
 * reorders the entry list, either of which changes the bytes of an already-issued legal document
 * while every mocked test stays green.
 *
 * Known-positive: drop V7 (or make `StatementMapper` stop writing `model_snapshot`) and
 * `the snapshot survives Postgres…` goes red — the row reads back with `snapshot == null`.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class StatementSnapshotRoundTripIT {

    @Inject
    lateinit var repository: StatementPeriodRepositoryImpl

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    lateinit var jdbcUrl: String

    @ConfigProperty(name = "quarkus.datasource.username")
    lateinit var dbUser: String

    @ConfigProperty(name = "quarkus.datasource.password")
    lateinit var dbPassword: String

    private val accountId: UUID = UUID.randomUUID()
    private val currency = "CZK"
    private val from: LocalDate = LocalDate.parse("2026-01-01")
    private val to: LocalDate = LocalDate.parse("2026-01-31")
    private val closedAt: Instant = Instant.parse("2026-02-01T02:30:00Z")

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    /** Trailing zeros are deliberate: `100.00` and `100.0` are the same number and different bytes. */
    private val snapshot = StatementSnapshot(
        iban = "CZ6508000000192000145399",
        holderName = "Jan Novák",
        entries = listOf(
            StatementEntry(
                entryRef = "TX-1",
                amount = BigDecimal("100.00"),
                currency = currency,
                creditDebit = CreditDebit.CRDT,
                bookingDate = LocalDate.parse("2026-01-15"),
                valueDate = LocalDate.parse("2026-01-16"),
                description = "Salary",
            ),
            StatementEntry(
                entryRef = "TX-2",
                amount = BigDecimal("25.50"),
                currency = currency,
                creditDebit = CreditDebit.DBIT,
                bookingDate = LocalDate.parse("2026-01-20"),
                valueDate = LocalDate.parse("2026-01-20"),
                description = "Fee",
                counterparty = "CZ9999",
            ),
        ),
    )

    private fun period(seq: Long, snap: StatementSnapshot?) = StatementPeriod(
        id = UUID.randomUUID(),
        accountId = accountId,
        pocketCurrency = currency,
        periodFrom = from,
        periodTo = to.plusDays(seq),
        legalSequenceNumber = seq,
        electronicSequenceNumber = seq,
        openingBalance = BigDecimal("1000.00"),
        closingBalance = BigDecimal("1074.50"),
        entryCount = snap?.entries?.size ?: 0,
        closedAt = closedAt,
        status = PeriodCloseStatus.CLOSED,
        snapshot = snap,
    )

    private fun event(aggregateId: UUID) = OutboxMessage(
        aggregateId = aggregateId,
        eventType = "account.statement.period.closed.v1",
        payload = """{"eventType":"account.statement.period.closed.v1"}""",
        createdAt = closedAt,
    )

    /** The raw column, read without the repository or the mapper — so a mapper bug cannot hide it. */
    private fun rawSnapshotColumn(seq: Long): String? =
        DriverManager.getConnection(jdbcUrl, dbUser, dbPassword).use { c -> firstSnapshot(c, seq) }

    private fun firstSnapshot(c: Connection, seq: Long): String? = c.prepareStatement(
        "SELECT model_snapshot FROM statement_period WHERE account_id = ? AND legal_sequence_number = ?",
    ).use { ps ->
        ps.setObject(1, accountId)
        ps.setLong(2, seq)
        ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
    }

    private fun modelOf(p: StatementPeriod, snap: StatementSnapshot) = StatementModel(
        accountId = p.accountId,
        iban = snap.iban,
        currency = p.pocketCurrency,
        holderName = snap.holderName,
        periodFrom = p.periodFrom,
        periodTo = p.periodTo,
        openingBalance = BalanceAnchor(p.openingBalance, p.pocketCurrency, p.periodFrom),
        closingBalance = BalanceAnchor(p.closingBalance, p.pocketCurrency, p.periodTo),
        entries = snap.entries,
        legalSequenceNumber = p.legalSequenceNumber,
        electronicSequenceNumber = p.electronicSequenceNumber,
        closedAt = p.closedAt,
    )

    @Test
    fun `the snapshot survives Postgres with scale and entry order intact, and re-renders byte-identically`() {
        val closed = period(seq = 1, snap = snapshot)
        onEventLoop { repository.saveWithOutbox(closed, event(closed.id)).awaitSuspending() }

        assertThat(rawSnapshotColumn(1L)).isNotNull().contains("TX-1", "Jan Novák")

        val reread = onEventLoop { repository.findBySequence(accountId, currency, 1L).awaitSuspending() }
        assertThat(reread).isNotNull
        val roundTripped = reread!!.snapshot
        assertThat(roundTripped).isNotNull

        // `isEqualTo` on the data class compares BigDecimal by equals(), which — unlike
        // compareTo() — is scale-sensitive. That is the point: 100.00 must not come back as 100.0.
        assertThat(roundTripped).isEqualTo(snapshot)
        assertThat(roundTripped!!.entries.map { it.entryRef }).containsExactly("TX-1", "TX-2")
        assertThat(roundTripped.entries[0].amount.toPlainString()).isEqualTo("100.00")
        assertThat(roundTripped.entries[1].counterparty).isEqualTo("CZ9999")

        // The bytes are what the customer receives, so assert on those and not only on the fields.
        for (format in StatementFormat.entries) {
            assertThat(StatementRenderer.render(modelOf(reread, roundTripped), format).body)
                .describedAs("re-render of %s from the persisted snapshot", format)
                .isEqualTo(StatementRenderer.render(modelOf(closed, snapshot), format).body)
        }
    }

    @Test
    fun `a period persisted without a snapshot reads back as null, not as an empty snapshot`() {
        // The render path branches on null to decide between "replay the frozen model" and "replay
        // live data". An empty-but-non-null snapshot would render a pre-#3986 statement as having
        // ZERO entries — a materially wrong legal document that reports as the deterministic path.
        val legacy = period(seq = 2, snap = null)
        onEventLoop { repository.saveWithOutbox(legacy, event(legacy.id)).awaitSuspending() }

        assertThat(rawSnapshotColumn(2L)).isNull()

        val reread = onEventLoop { repository.findBySequence(accountId, currency, 2L).awaitSuspending() }
        assertThat(reread).isNotNull
        assertThat(reread!!.snapshot).isNull()
    }
}
