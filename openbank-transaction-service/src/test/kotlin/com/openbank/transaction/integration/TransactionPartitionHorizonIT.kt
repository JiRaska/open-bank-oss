// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.transaction.integration

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.Money
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.transaction.domain.model.Transaction
import com.openbank.transaction.domain.model.TransactionStatus
import com.openbank.transaction.domain.model.TransactionType
import com.openbank.transaction.infrastructure.partition.TransactionPartitionMaintainer
import com.openbank.transaction.infrastructure.persistence.repository.PanacheTransactionRepository
import com.openbank.transaction.it.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * #17101: `transactions` had partitions for 2025/2026 + DEFAULT only, so every 2027 row would
 * land in `transactions_default` and then block creating `transactions_2027`. Against the real
 * migrated database this asserts the V25 horizon, the routing of a 2027 row, the maintainer's
 * rolling window measured from TODAY (so it goes red if the horizon ever drifts under 12 months),
 * and the migration's fail-loud guard — executed from the real V25 file, not a copy.
 *
 * Reads go through plain JDBC so the assertion is about the catalog, not about our own adapter.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaTestResource::class)
class TransactionPartitionHorizonIT {

    @Inject
    lateinit var repository: PanacheTransactionRepository

    @Inject
    lateinit var maintainer: TransactionPartitionMaintainer

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun <T> jdbc(block: (Connection) -> T): T {
        val cfg = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            cfg.getValue("quarkus.datasource.jdbc.url", String::class.java),
            cfg.getValue("quarkus.datasource.username", String::class.java),
            cfg.getValue("quarkus.datasource.password", String::class.java),
        ).use(block)
    }

    /** Child partition name -> exclusive upper bound (null for DEFAULT). */
    private fun partitions(schema: String = "public"): Map<String, LocalDate?> = jdbc { c ->
        c.prepareStatement(
            """
            select c.relname, pg_get_expr(c.relpartbound, c.oid)
            from pg_inherits i
            join pg_class c on c.oid = i.inhrelid
            join pg_class p on p.oid = i.inhparent
            join pg_namespace n on n.oid = p.relnamespace
            where p.relname = 'transactions' and n.nspname = ?
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, schema)
            ps.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) {
                        val upper = UPPER_BOUND.find(rs.getString(2))?.groupValues?.get(1)?.let(LocalDate::parse)
                        put(rs.getString(1), upper)
                    }
                }
            }
        }
    }

    @Test
    fun `V25 pre-created the partition horizon through 2028`() {
        val parts = partitions()
        assertThat(parts.keys).contains(
            "transactions_2025",
            "transactions_2026",
            "transactions_2027",
            "transactions_2028",
            "transactions_default",
        )
        assertThat(parts["transactions_2028"]).isEqualTo(LocalDate.of(2029, 1, 1))
    }

    @Test
    fun `a transaction booked on 2027-06-01 lands in transactions_2027, not DEFAULT`() {
        val id = Ids.newId()
        val czk = CurrencyCode("CZK")
        val day = LocalDate.of(2027, 6, 1)
        val now = Instant.now()
        val tx = Transaction(
            id = id,
            referenceNumber = "TX-PART-$id",
            type = TransactionType.TRANSFER,
            sourceAccountId = Ids.newId(),
            targetAccountId = Ids.newId(),
            amount = Money(BigDecimal("10.00"), czk),
            fxRate = null,
            baseAmount = Money(BigDecimal("10.00"), czk),
            status = TransactionStatus.COMPLETED,
            description = "partition routing fixture",
            valueDate = day,
            bookingDate = day,
            initiatedAt = now,
            completedAt = now,
            failedAt = null,
            failureReason = null,
            idempotencyKey = "idem-part-$id",
            version = 0,
        )
        val outbox = OutboxMessage(
            aggregateId = id,
            eventType = "test.event.partition-routing",
            payload = """{"id":"$id"}""",
            createdAt = now,
        )
        onEventLoop { repository.save(tx, outbox) }

        val partition = jdbc { c ->
            c.prepareStatement("select tableoid::regclass::text from transactions where id = ?").use { ps ->
                ps.setObject(1, id)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }
        assertThat(partition).isEqualTo("transactions_2027")
    }

    @Test
    fun `after a maintenance pass the newest partition ends at least 12 months from today`() {
        onEventLoop { maintainer.maintain() }

        val newestUpper = partitions().values.filterNotNull().max()
        val floor = LocalDate.now().plusMonths(12)
        assertThat(newestUpper)
            .describedAs(
                "newest transactions partition ends %s, less than 12 months out (%s) — " +
                    "rows will soon fall into transactions_default (#17101)",
                newestUpper,
                floor,
            )
            .isAfterOrEqualTo(floor)
    }

    @Test
    fun `V25 refuses loudly when DEFAULT already holds rows for the new range`() {
        withScratchTransactions(misroutedRow = true) { c, schema ->
            assertThatThrownBy { c.createStatement().use { it.execute(v25()) } }
                .isInstanceOf(SQLException::class.java)
                .hasMessageContaining("transactions_default holds 1 row(s) with booking_date in 2027-2028")
            c.rollback()
            assertThat(partitions(schema).keys).doesNotContain("transactions_2027", "transactions_2028")
        }
    }

    @Test
    fun `V25 creates the horizon when DEFAULT holds no rows for the new range`() {
        withScratchTransactions(misroutedRow = false) { c, schema ->
            c.createStatement().use { it.execute(v25()) }
            c.commit()
            assertThat(partitions(schema).keys).contains("transactions_2027", "transactions_2028")
        }
    }

    private fun v25(): String = requireNotNull(
        javaClass.classLoader.getResource("db/migration/V25__transactions_partition_horizon.sql"),
    ) { "V25 migration not on the classpath" }.readText()

    /**
     * Runs the real V25 file against a throwaway schema whose `transactions` mirrors V1's
     * partitioning, so the guard is exercised without touching the service's own tables.
     */
    private fun withScratchTransactions(misroutedRow: Boolean, block: (Connection, String) -> Unit) {
        val schema = "txpart_" + UUID.randomUUID().toString().replace("-", "").take(12)
        jdbc { c ->
            try {
                c.createStatement().use { st ->
                    st.execute("create schema $schema")
                    st.execute("set search_path to $schema, public")
                    st.execute(
                        "create table transactions (id uuid not null, booking_date date not null) " +
                            "partition by range (booking_date)",
                    )
                    st.execute(
                        "create table transactions_2026 partition of transactions " +
                            "for values from ('2026-01-01') to ('2027-01-01')",
                    )
                    st.execute("create table transactions_default partition of transactions default")
                    if (misroutedRow) {
                        st.execute("insert into transactions values (gen_random_uuid(), date '2027-03-01')")
                    }
                }
                c.autoCommit = false
                block(c, schema)
            } finally {
                c.rollback()
                c.autoCommit = true
                c.createStatement().use { it.execute("drop schema if exists $schema cascade") }
            }
        }
    }

    private companion object {
        val UPPER_BOUND = Regex("""TO \('(\d{4}-\d{2}-\d{2})'\)""")
    }
}
