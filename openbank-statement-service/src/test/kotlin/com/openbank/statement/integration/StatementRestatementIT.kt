// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.integration

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.statement.domain.model.PeriodCloseStatus
import com.openbank.statement.domain.model.StatementPeriod
import com.openbank.statement.infrastructure.persistence.repository.StatementPeriodRepositoryImpl
import com.openbank.statement.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.http.TestHTTPResource
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
import java.net.URL
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Real-DB coverage for the statement restatement path (ADR-0035 §D, issue #1302 item 5).
 *
 * This IT exists because **no mocked-repository test can see either defect this path is exposed to**:
 *
 *  1. `ux_statement_period_window` (V1) was UNIQUE on (account, pocket, period_from, period_to), so a
 *     superseding close for the same window is a **duplicate-key violation at flush** — the schema
 *     made ADR-0035 §D physically impossible. `V6__statement_period_restatement.sql` narrows it to
 *     the invariant that actually holds (at most one *non-SUPERSEDED* close per window). Removing V6
 *     turns `restatement writes a second row …` red with a
 *     `duplicate key value violates unique constraint` — that is this test's known-positive.
 *  2. The status flip on the prior record must be an **UPDATE**. `StatementPeriodEntity.id` is
 *     application-assigned, so routing it through `persist()` would schedule an INSERT and fail with
 *     the same duplicate-key error (the defect that 500'd every consent-service lifecycle
 *     transition). Only a real session/flush can distinguish the two.
 *
 * Rows are read back with a plain JDBC query rather than through the repository, so a mapper or
 * query-filter mistake cannot hide the persisted truth from the assertion.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class StatementRestatementIT {

    @Inject
    lateinit var repository: StatementPeriodRepositoryImpl

    @TestHTTPResource("/q/openapi")
    lateinit var openapiUrl: URL

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

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun period(
        seq: Long,
        opening: String,
        closing: String,
        entryCount: Int,
        supersedes: Long? = null,
        periodFrom: LocalDate = from,
        periodTo: LocalDate = to,
    ) = StatementPeriod(
        id = UUID.randomUUID(),
        accountId = accountId,
        pocketCurrency = currency,
        periodFrom = periodFrom,
        periodTo = periodTo,
        legalSequenceNumber = seq,
        electronicSequenceNumber = seq,
        openingBalance = BigDecimal(opening),
        closingBalance = BigDecimal(closing),
        entryCount = entryCount,
        closedAt = Instant.parse("2026-02-01T02:30:00Z"),
        status = PeriodCloseStatus.CLOSED,
        supersedesSequence = supersedes,
    )

    private fun event(aggregateId: UUID, type: String) = OutboxMessage(
        aggregateId = aggregateId,
        eventType = type,
        payload = """{"eventType":"$type"}""",
        createdAt = Instant.parse("2026-02-01T02:30:00Z"),
    )

    /** Reads the persisted rows for this account straight out of Postgres — no repository, no mapper. */
    private fun rowsFromDb(): List<Triple<Long, String, BigDecimal>> =
        DriverManager.getConnection(jdbcUrl, dbUser, dbPassword).use { c ->
            c.prepareStatement(
                "SELECT legal_sequence_number, status, closing_balance, supersedes_sequence, entry_count " +
                    "FROM statement_period WHERE account_id = ? ORDER BY legal_sequence_number",
            ).use { ps ->
                ps.setObject(1, accountId)
                ps.executeQuery().use { rs ->
                    generateSequence {
                        if (rs.next()) {
                            Triple(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3))
                        } else {
                            null
                        }
                    }.toList()
                }
            }
        }

    private fun supersedesSequenceOf(seq: Long): Long? = queryLong(
        "SELECT supersedes_sequence FROM statement_period WHERE account_id = ? AND legal_sequence_number = ?",
        seq,
    )

    private fun queryLong(sql: String, seq: Long): Long? =
        DriverManager.getConnection(jdbcUrl, dbUser, dbPassword).use { c -> firstLong(c, sql, seq) }

    private fun firstLong(c: java.sql.Connection, sql: String, seq: Long): Long? = c.prepareStatement(sql).use { ps ->
        ps.setObject(1, accountId)
        ps.setLong(2, seq)
        ps.executeQuery().use { rs -> if (rs.next()) (rs.getObject(1) as? Number)?.toLong() else null }
    }

    @Test
    fun `restatement writes a second row for the same window and flips the prior one to SUPERSEDED`() {
        val original = period(seq = 1, opening = "1000.00", closing = "1100.00", entryCount = 1)
        onEventLoop { repository.saveWithOutbox(original, event(original.id, "period.closed")).awaitSuspending() }

        val replacement = period(seq = 2, opening = "1000.00", closing = "1075.00", entryCount = 2, supersedes = 1)
        onEventLoop {
            repository.supersedeAndReplace(original.id, replacement, event(replacement.id, "period.restated"))
                .awaitSuspending()
        }

        val rows = rowsFromDb()
        assertThat(rows).hasSize(2)

        // The original page is retained verbatim, only its status changes — the legal record of what
        // was issued must stay reproducible.
        val (seq1, status1, closing1) = rows[0]
        assertThat(seq1).isEqualTo(1L)
        assertThat(status1).isEqualTo("SUPERSEDED")
        assertThat(closing1).isEqualByComparingTo("1100.00")

        // The correction carries the next sequence and the exact corrected figures.
        val (seq2, status2, closing2) = rows[1]
        assertThat(seq2).isEqualTo(2L)
        assertThat(status2).isEqualTo("CLOSED")
        assertThat(closing2).isEqualByComparingTo("1075.00")
        assertThat(supersedesSequenceOf(2L)).isEqualTo(1L)
    }

    @Test
    fun `the standing close for a restated window is the replacement, and the superseded page is still renderable`() {
        val original = period(seq = 1, opening = "1000.00", closing = "1100.00", entryCount = 1)
        onEventLoop { repository.saveWithOutbox(original, event(original.id, "period.closed")).awaitSuspending() }
        val replacement = period(seq = 2, opening = "1000.00", closing = "1075.00", entryCount = 2, supersedes = 1)
        onEventLoop {
            repository.supersedeAndReplace(original.id, replacement, event(replacement.id, "period.restated"))
                .awaitSuspending()
        }

        // findByPeriod drives close-idempotency: it must resolve to the CORRECTION, never the stale page.
        val standing = onEventLoop { repository.findByPeriod(accountId, currency, from, to).awaitSuspending() }
        assertThat(standing).isNotNull
        assertThat(standing!!.legalSequenceNumber).isEqualTo(2L)
        assertThat(standing.closingBalance).isEqualByComparingTo("1075.00")
        assertThat(standing.status).isEqualTo(PeriodCloseStatus.CLOSED)

        // …while the superseded page stays reachable by its legal sequence for its full retention.
        val issued = onEventLoop { repository.findBySequence(accountId, currency, 1L).awaitSuspending() }
        assertThat(issued).isNotNull
        assertThat(issued!!.status).isEqualTo(PeriodCloseStatus.SUPERSEDED)
        assertThat(issued.closingBalance).isEqualByComparingTo("1100.00")
    }

    @Test
    fun `the next period opens on the corrected closing balance, not the superseded one`() {
        // Both rows share periodTo, so an unfiltered "latest prior close" query picks between them
        // non-deterministically — and picking the superseded one would silently open February on a
        // balance the bank has already retracted, propagating the error forward forever.
        val original = period(seq = 1, opening = "1000.00", closing = "1100.00", entryCount = 1)
        onEventLoop { repository.saveWithOutbox(original, event(original.id, "period.closed")).awaitSuspending() }
        val replacement = period(seq = 2, opening = "1000.00", closing = "1075.00", entryCount = 2, supersedes = 1)
        onEventLoop {
            repository.supersedeAndReplace(original.id, replacement, event(replacement.id, "period.restated"))
                .awaitSuspending()
        }

        val feb = LocalDate.parse("2026-02-01")
        val opening = onEventLoop { repository.priorClosing(accountId, currency, feb).awaitSuspending() }

        assertThat(opening).isNotNull
        assertThat(opening!!).isEqualByComparingTo("1075.00")
    }

    @Test
    fun `the running app actually serves the restate route`() {
        // The committed openapi.yaml is a document; this asks the RUNNING app what it registered.
        // A Kotlin annotation binds to the NEXT declaration, so a `@Path`/`@POST` that silently
        // failed to attach to the resource method leaves every call 404 while unit tests that
        // invoke the class directly stay green (the McpEndpoint defect). /q/openapi is generated
        // from the scanned JAX-RS annotations (NOT from the committed openapi.yaml, which lives
        // outside META-INF and is never merged), so a missing entry here means an unserved route.
        val served = openapiUrl.openStream().bufferedReader().use { it.readText() }

        assertThat(served).contains("/api/v1/statements/{accountId}/{currency}/restate")
        // Known-positive for this assertion: an endpoint that has always been served.
        assertThat(served).contains("/api/v1/statements/{accountId}/close")
    }
}
