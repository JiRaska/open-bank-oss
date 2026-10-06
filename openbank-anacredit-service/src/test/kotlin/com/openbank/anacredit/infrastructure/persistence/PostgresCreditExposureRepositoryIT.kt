// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.anacredit.infrastructure.persistence

import com.openbank.anacredit.domain.model.CounterpartyType
import com.openbank.anacredit.domain.model.CreditExposure
import com.openbank.anacredit.domain.model.InstrumentType
import com.openbank.anacredit.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Comparator
import java.util.UUID
import javax.sql.DataSource

/**
 * Repository round-trip against a real Testcontainers PostgreSQL (ADR-0037 v2) — the one thing pure
 * domain/unit tests cannot cover: that Flyway's `credit_exposures` schema and reactive Panache
 * actually persist and read back a [CreditExposure].
 *
 * [PostgresCreditExposureRepository] is reactive (`Mutiny.SessionFactory.withSession/withTransaction`
 * bridged to `suspend`), so its calls MUST run on a Vert.x duplicated context — a plain test thread
 * has none and fails with "No current Vertx context found". [onVertxContext] bridges the suspend
 * body via [VertxContextSupport.subscribeAndAwait] (mirrors ledger-service's
 * `JournalPartitionMaintainerIT`). Each test declares an explicit `: Unit` return — `fun x() = expr`
 * inferring a non-`Unit` type silently drops the test under JUnit5/Kotlin.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PostgresCreditExposureRepositoryIT {

    @Inject
    lateinit var repository: PostgresCreditExposureRepository

    @Inject
    lateinit var dataSource: DataSource

    private data class Observation(
        val version: Long,
        val capturedAt: OffsetDateTime,
        val kind: String,
        val drawn: BigDecimal,
    )

    private fun observations(instrumentId: String): List<Observation> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT version_id, captured_at, event_kind, drawn_amount FROM credit_exposure_observation " +
                "WHERE instrument_id = ? ORDER BY version_id",
        ).use { statement ->
            statement.setString(1, instrumentId)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            Observation(
                                rows.getLong(1),
                                rows.getObject(2, OffsetDateTime::class.java),
                                rows.getString(3),
                                rows.getBigDecimal(4),
                            ),
                        )
                    }
                }
            }
        }
    }

    @Suppress("NestedBlockDepth") // JDBC connection, statement and result must all close in the test
    private fun observedAt(instrumentId: String, checkpoint: Long): BigDecimal? {
        val query = "SELECT drawn_amount FROM credit_exposure_observation " +
            "WHERE instrument_id = ? AND version_id <= ? ORDER BY version_id DESC LIMIT 1"
        return dataSource.connection.use { connection ->
            connection.prepareStatement(query).use { statement ->
                statement.setString(1, instrumentId)
                statement.setLong(2, checkpoint)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getBigDecimal(1) else null }
            }
        }
    }

    @Suppress("NestedBlockDepth") // JDBC connection, statement and result must all close in the test
    private fun observedAt(instrumentId: String, cutoff: OffsetDateTime): BigDecimal? {
        val query = "SELECT drawn_amount FROM credit_exposure_observation " +
            "WHERE instrument_id = ? AND captured_at <= ? ORDER BY captured_at DESC, version_id DESC LIMIT 1"
        return dataSource.connection.use { connection ->
            connection.prepareStatement(query).use { statement ->
                statement.setString(1, instrumentId)
                statement.setObject(2, cutoff)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getBigDecimal(1) else null }
            }
        }
    }

    private fun <T> onVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    private fun exposure(instrumentId: String, drawnAmount: BigDecimal = BigDecimal("12000.00")) = CreditExposure(
        instrumentId = instrumentId,
        debtorId = "LE-IT-ACME",
        debtorType = CounterpartyType.LEGAL_ENTITY,
        instrumentType = InstrumentType.OVERDRAFT,
        currency = "EUR",
        committedAmount = BigDecimal("40000.00"),
        drawnAmount = drawnAmount,
        committedAmountEur = BigDecimal("40000.00"),
        arrearsAmount = BigDecimal.ZERO,
        defaulted = false,
        originationDate = LocalDate.parse("2025-06-01"),
    )

    @Test
    fun `save then find round-trips every field through Postgres`(): Unit = onVertxContext {
        val saved = repository.upsert(exposure("OD-IT-1"))

        val found = repository.findById("OD-IT-1")

        assertThat(found).isNotNull
        // Postgres NUMERIC(20,2) always reads back scale 2 (e.g. "0.00"), while a BigDecimal.ZERO
        // fixture has scale 0 — same value, different BigDecimal#equals result. Compare BigDecimal
        // fields by numeric value (compareTo), not object equality.
        assertThat(found)
            .usingRecursiveComparison()
            .withComparatorForType(Comparator.naturalOrder(), BigDecimal::class.java)
            .isEqualTo(saved)
    }

    @Test
    fun `upsert on an existing instrumentId updates the row rather than duplicating it`(): Unit = onVertxContext {
        repository.upsert(exposure("OD-IT-2", drawnAmount = BigDecimal("12000.00")))
        repository.upsert(exposure("OD-IT-2", drawnAmount = BigDecimal("18500.00")))

        val found = repository.findById("OD-IT-2")

        assertThat(found).isNotNull
        assertThat(found!!.drawnAmount).isEqualByComparingTo(BigDecimal("18500.00"))
        assertThat(repository.listAll().count { it.instrumentId == "OD-IT-2" }).isEqualTo(1)
    }

    @Test
    fun `findById returns null for an instrument that was never registered`(): Unit = onVertxContext {
        assertThat(repository.findById("OD-IT-DOES-NOT-EXIST")).isNull()
    }

    @Test
    fun `listAll returns every persisted exposure`(): Unit = onVertxContext {
        repository.upsert(exposure("OD-IT-LIST-1"))
        repository.upsert(exposure("OD-IT-LIST-2"))

        val all = repository.listAll().map { it.instrumentId }

        assertThat(all).contains("OD-IT-LIST-1", "OD-IT-LIST-2")
    }

    @Test
    fun `committed upserts keep prior observed values while latest projection updates`() {
        val id = "OD-HISTORY-${UUID.randomUUID()}"
        onVertxContext { repository.upsert(exposure(id, BigDecimal("12000.00"))) }
        val first = observations(id).single()
        onVertxContext { repository.upsert(exposure(id, BigDecimal("18500.00"))) }
        val history = observations(id)

        assertThat(history.map { it.kind }).containsExactly("INSERT", "UPDATE")
        assertThat(observedAt(id, first.version)).isEqualByComparingTo(BigDecimal("12000.00"))
        assertThat(history.last().capturedAt).isAfter(first.capturedAt)
        assertThat(observedAt(id, first.capturedAt)).isEqualByComparingTo(BigDecimal("12000.00"))
        assertThat(history.last().drawn).isEqualByComparingTo(BigDecimal("18500.00"))
        assertThat(onVertxContext { repository.findById(id) }!!.drawnAmount)
            .isEqualByComparingTo(BigDecimal("18500.00"))
        assertThat(onVertxContext { repository.listAll() }.count { it.instrumentId == id }).isEqualTo(1)
    }

    @Test
    fun `rolled-back source writes leave no observation`() {
        val id = "OD-ROLLBACK-${UUID.randomUUID()}"
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.prepareStatement(
                """
                INSERT INTO credit_exposures (
                    instrument_id, debtor_id, debtor_type, instrument_type, currency,
                    committed_amount, drawn_amount, committed_amount_eur, arrears_amount,
                    defaulted, origination_date
                ) VALUES (?, 'LE-IT-ACME', 'LEGAL_ENTITY', 'OVERDRAFT', 'EUR',
                          40000, 12000, 40000, 0, false, DATE '2025-06-01')
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, id)
                statement.executeUpdate()
            }
            connection.rollback()
        }
        assertThat(observations(id)).isEmpty()
        assertThat(onVertxContext { repository.findById(id) }).isNull()
    }

    @Test
    fun `deleting the current projection leaves a terminal observation`() {
        val id = "OD-DELETE-${UUID.randomUUID()}"
        onVertxContext { repository.upsert(exposure(id)) }
        dataSource.connection.use { connection ->
            connection.prepareStatement("DELETE FROM credit_exposures WHERE instrument_id = ?").use { statement ->
                statement.setString(1, id)
                assertThat(statement.executeUpdate()).isEqualTo(1)
            }
        }

        assertThat(observations(id).map { it.kind }).containsExactly("INSERT", "DELETE")
        assertThat(onVertxContext { repository.findById(id) }).isNull()
    }
}
