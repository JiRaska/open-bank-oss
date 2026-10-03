// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.integration

import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.ConfigProvider
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * The loan contract number (#11107, V24) against real Postgres: uniqueness under concurrent
 * creation, the trigger backstop, immutability, the UTC year boundary, and a DETERMINISTIC
 * backfill of loans that existed before V24.
 *
 * Plain JDBC on purpose: the generator is a database function, so a mocked repository cannot say
 * anything about it, and the reactive session cannot be driven from the test thread (HR000068).
 * The repository path (`LoanRepositoryImpl.save` drawing the number before INSERT, and the
 * `loan.disbursed` payload carrying it) is asserted end to end by [LendingOutboxWriteIT].
 *
 * Years far in the future keep this class's counters apart from every other test sharing the
 * container; assertions are RELATIVE to the counter's value before the test, so a warm container
 * that already holds rows for that year changes nothing.
 */
@QuarkusTest
@QuarkusTestResource(LoanContractNumberIT.InMemoryKafkaResource::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_lending_it")],
)
class LoanContractNumberIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> {
            val props = InMemoryConnector.switchOutgoingChannelsToInMemory("lending-events-out").toMutableMap()
            props["quarkus.kafka.devservices.enabled"] = "false"
            props["openbank.outbox.dispatch-enabled"] = "false"
            return props
        }

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var dataSource: DataSource

    @Test
    fun `concurrent creations draw distinct, consecutive numbers for one year`() {
        val year = CONCURRENT_YEAR
        val before = counter(year)
        val pool = Executors.newFixedThreadPool(THREADS)
        val start = CountDownLatch(1)
        try {
            val futures = (1..THREADS).map {
                pool.submit<List<UUID>> {
                    start.await()
                    (1..PER_THREAD).map { insertLoan(createdAtUtc = "$year-03-01 12:00:00+00") }
                }
            }
            start.countDown()
            val numbers = futures.flatMap { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                .map { loanId -> contractNumberOf(loanId) }

            assertThat(numbers).hasSize(THREADS * PER_THREAD).doesNotHaveDuplicates()
            assertThat(numbers).allMatch { it.matches(Regex("UV-$year-\\d{6,}")) }
            val expected = ((before + 1)..(before + THREADS * PER_THREAD)).map { "UV-$year-%06d".format(it) }
            // Every commit succeeded, so nothing rolled back: the run is gap-free here even though
            // the scheme only promises gap-TOLERANCE.
            assertThat(numbers).containsExactlyInAnyOrderElementsOf(expected)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `the year is the UTC year of created_at`() {
        val before = counter(BOUNDARY_YEAR + 1)
        // 23:30 at UTC-01:00 on 31 December is already 00:30 UTC on 1 January.
        val loanId = insertLoan(createdAtUtc = "$BOUNDARY_YEAR-12-31 23:30:00-01")
        assertThat(contractNumberOf(loanId)).isEqualTo("UV-${BOUNDARY_YEAR + 1}-%06d".format(before + 1))
    }

    @Test
    fun `a contract number cannot be changed once assigned`() {
        val loanId = insertLoan(createdAtUtc = "$CONCURRENT_YEAR-05-01 08:00:00+00")
        dataSource.connection.use { c ->
            assertThatThrownBy {
                c.prepareStatement("UPDATE loan SET contract_number = 'UV-1999-000001' WHERE id = ?").use { ps ->
                    ps.setObject(1, loanId)
                    ps.executeUpdate()
                }
            }.isInstanceOf(SQLException::class.java).hasMessageContaining("immutable")
        }
        // A write that leaves the column alone is unaffected.
        dataSource.connection.use { c ->
            c.prepareStatement("UPDATE loan SET version = version + 1 WHERE id = ?").use { ps ->
                ps.setObject(1, loanId)
                assertThat(ps.executeUpdate()).isEqualTo(1)
            }
        }
    }

    @Test
    fun `the backfill numbers pre-existing loans by created_at then id, whatever the insert order`() {
        // Two fresh schemas migrated to V20 (the last version below V24 on main), seeded with the
        // SAME loans in OPPOSITE physical order, then taken through V24.
        // A backfill that depended on heap order would disagree.
        val loans = backfillFixture()
        val first = backfill("bf_forward_${suffix()}", loans)
        val second = backfill("bf_reverse_${suffix()}", loans.reversed())

        assertThat(first).isEqualTo(second)
        assertThat(first).isEqualTo(
            mapOf(
                // 2024: two loans at the SAME instant are ordered by id.
                ID_A to "UV-2024-000001",
                ID_B to "UV-2024-000002",
                ID_C to "UV-2024-000003",
                // 2025 restarts at 1.
                ID_D to "UV-2025-000001",
            ),
        )
    }

    @Test
    fun `after the backfill the counter continues past the backfilled numbers`() {
        val schema = "bf_continue_${suffix()}"
        backfill(schema, backfillFixture())
        rawConnection().use { c ->
            c.createStatement().use { it.execute("SET search_path TO $schema") }
            c.createStatement().use { s ->
                s.executeQuery("SELECT next_loan_contract_number(2024)").use { rs ->
                    rs.next()
                    assertThat(rs.getString(1)).isEqualTo("UV-2024-000004")
                }
            }
            c.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private data class SeedLoan(val id: UUID, val createdAt: String)

    private fun backfillFixture() = listOf(
        SeedLoan(ID_C, "2024-08-01 10:00:00+00"),
        SeedLoan(ID_B, "2024-02-01 10:00:00+00"),
        SeedLoan(ID_A, "2024-02-01 10:00:00+00"),
        SeedLoan(ID_D, "2025-01-15 10:00:00+00"),
    )

    private fun backfill(schema: String, loans: List<SeedLoan>): Map<UUID, String> {
        flyway(schema, target = "20").migrate()
        rawConnection().use { c ->
            c.createStatement().use { it.execute("SET search_path TO $schema") }
            val app = UUID.randomUUID()
            c.createStatement().use { s ->
                s.execute(
                    "INSERT INTO loan_application (id, party_id, requested_amount, currency, nominal_annual_rate, " +
                        "term_periods, first_due_date, status, proposed_by) VALUES ('$app', '${UUID.randomUUID()}', " +
                        "1000.00, 'CZK', 0.05, 12, DATE '2024-03-01', 'DISBURSED', 'backfill-it')",
                )
            }
            loans.forEach { l -> c.createStatement().use { it.execute(loanInsert(l.id, app, l.createdAt)) } }
        }
        flyway(schema, target = "24").migrate()
        val result = rawConnection().use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT id, contract_number FROM $schema.loan").use { rs ->
                    buildMap { while (rs.next()) put(rs.getObject(1, UUID::class.java), rs.getString(2)) }
                }
            }
        }
        if (!schema.startsWith("bf_continue")) {
            rawConnection().use { c -> c.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") } }
        }
        return result
    }

    private fun flyway(schema: String, target: String): Flyway {
        val config = ConfigProvider.getConfig()
        return Flyway.configure()
            .dataSource(
                config.getValue("quarkus.datasource.jdbc.url", String::class.java),
                config.getValue("quarkus.datasource.username", String::class.java),
                config.getValue("quarkus.datasource.password", String::class.java),
            )
            .schemas(schema)
            .createSchemas(true)
            .locations("classpath:db/migration")
            .target(target)
            .load()
    }

    private fun rawConnection(): Connection {
        val config = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        )
    }

    /** Raw INSERT with no contract_number — the V24 trigger assigns it. Returns the loan id. */
    private fun insertLoan(createdAtUtc: String): UUID {
        val app = UUID.randomUUID()
        val loan = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.createStatement().use { s ->
                s.execute(
                    "INSERT INTO loan_application (id, party_id, requested_amount, currency, nominal_annual_rate, " +
                        "term_periods, first_due_date, status, proposed_by) VALUES ('$app', '${UUID.randomUUID()}', " +
                        "1000.00, 'CZK', 0.05, 12, DATE '2030-01-01', 'DISBURSED', 'contract-number-it')",
                )
                s.execute(loanInsert(loan, app, createdAtUtc))
            }
        }
        return loan
    }

    private fun loanInsert(id: UUID, app: UUID, createdAt: String) =
        "INSERT INTO loan (id, application_id, party_id, principal, currency, nominal_annual_rate, term_periods, " +
            "method, first_due_date, disbursed_at, created_at) VALUES ('$id', '$app', '${UUID.randomUUID()}', " +
            "1000.00, 'CZK', 0.05, 12, 'ANNUITY', DATE '2030-01-01', TIMESTAMPTZ '$createdAt', " +
            "TIMESTAMPTZ '$createdAt')"

    private fun contractNumberOf(loanId: UUID): String = dataSource.connection.use { c ->
        c.prepareStatement("SELECT contract_number FROM loan WHERE id = ?").use { ps ->
            ps.setObject(1, loanId)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "loan $loanId not found" }
                rs.getString(1)
            }
        }
    }

    private fun counter(year: Int): Long = dataSource.connection.use { c ->
        c.createStatement().use { s ->
            s.executeQuery(
                "SELECT COALESCE((SELECT last_value FROM loan_contract_number_counter WHERE year = $year), 0)",
            ).use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }

    private fun suffix() = UUID.randomUUID().toString().take(SUFFIX_LENGTH).replace("-", "")

    private companion object {
        const val CONCURRENT_YEAR = 2077
        const val BOUNDARY_YEAR = 2088
        const val THREADS = 8
        const val PER_THREAD = 10
        const val TIMEOUT_SECONDS = 60L
        const val SUFFIX_LENGTH = 8
        val ID_A: UUID = UUID.fromString("00000000-0000-7000-8000-00000000000a")
        val ID_B: UUID = UUID.fromString("00000000-0000-7000-8000-00000000000b")
        val ID_C: UUID = UUID.fromString("00000000-0000-7000-8000-00000000000c")
        val ID_D: UUID = UUID.fromString("00000000-0000-7000-8000-00000000000d")
    }
}
