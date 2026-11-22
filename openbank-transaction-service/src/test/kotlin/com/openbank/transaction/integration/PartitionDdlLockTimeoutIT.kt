// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.transaction.integration

import com.openbank.libs.observability.WorkflowLivenessMetrics
import com.openbank.libs.persistence.partition.PartitionManager
import com.openbank.transaction.infrastructure.partition.HibernatePartitionExecutor
import com.openbank.transaction.infrastructure.partition.TransactionPartitionMaintainer
import com.openbank.transaction.it.PostgresRedpandaTestResource
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Follow-up to #17101: partition DDL (`CREATE TABLE ... PARTITION OF`) takes ACCESS EXCLUSIVE on
 * the parent. With no `lock_timeout`, a long transaction holding any lock on `transactions` makes
 * the DDL queue forever and every booking queues behind it. Here a second connection holds an
 * ACCESS SHARE lock on the parent and the DDL must FAIL within the bound instead of hanging, and
 * the maintainer must record no success (the liveness alert is what pages).
 *
 * The profile asks for a horizon beyond the V25 partitions so `maintain()` genuinely has a CREATE
 * to run (an existing partition is a no-op that never touches the parent lock), and pushes the
 * scheduler's first tick out of reach so only this test drives the maintainer.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaTestResource::class)
@TestProfile(PartitionDdlLockTimeoutIT.BeyondHorizonProfile::class)
class PartitionDdlLockTimeoutIT {

    class BeyondHorizonProfile : QuarkusTestProfile {
        // Literals only: a QuarkusTestProfile loads in a different classloader from the test.
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.transaction.partition.future-years" to "6",
            "openbank.transaction.partition.delayed" to "1h",
        )
    }

    @Inject
    lateinit var executor: HibernatePartitionExecutor

    @Inject
    lateinit var maintainer: TransactionPartitionMaintainer

    @Inject
    lateinit var registry: MeterRegistry

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun jdbcConnection(): Connection {
        val cfg = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            cfg.getValue("quarkus.datasource.jdbc.url", String::class.java),
            cfg.getValue("quarkus.datasource.username", String::class.java),
            cfg.getValue("quarkus.datasource.password", String::class.java),
        )
    }

    /** Holds a lock on the parent in another session, exactly like a long-running transaction. */
    private fun <T> whileParentIsLocked(block: () -> T): T = jdbcConnection().use { holder ->
        holder.autoCommit = false
        holder.createStatement().use { it.execute("lock table transactions in access share mode") }
        try {
            block()
        } finally {
            holder.rollback()
        }
    }

    private fun successRecorded(): Double? = registry
        .find(WorkflowLivenessMetrics.SUCCESS_RECORDED)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, "transaction-partition-maintenance")
        .gauge()
        ?.value()

    private fun partitionExists(name: String): Boolean = jdbcConnection().use { c ->
        c.prepareStatement("select to_regclass(?) is not null").use { ps ->
            ps.setString(1, name)
            ps.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
        }
    }

    private fun Throwable.chainText(): String = generateSequence(this) {
        it.cause
    }.joinToString(" | ") { it.toString() }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `partition DDL fails within the lock timeout instead of queueing behind a lock on the parent`() {
        val partition = PartitionManager.yearlyPartition("transactions", 2031)
        val ddl = PartitionManager.createPartitionDdl("transactions", partition)

        val started = System.nanoTime()
        whileParentIsLocked {
            assertThatThrownBy { onEventLoop { executor.executeDdl(ddl) } }
                .describedAs("DDL must give up on the lock, not hang")
                .satisfies({ ex -> assertThat(ex.chainText()).containsIgnoringCase("lock timeout") })
        }
        val waited = Duration.ofNanos(System.nanoTime() - started)

        // Configured default is 5s: it must have actually WAITED for it (so the bound is the
        // configured one, not an unrelated error) and must not have hung.
        assertThat(waited).isGreaterThanOrEqualTo(Duration.ofSeconds(4)).isLessThan(Duration.ofSeconds(20))
        assertThat(partitionExists(partition.name)).describedAs("a timed-out DDL must roll back").isFalse()

        // Positive control: with the lock released the very same DDL succeeds, so the failure
        // above was the lock and not a broken statement.
        onEventLoop { executor.executeDdl(ddl) }
        assertThat(partitionExists(partition.name)).isTrue()
        onEventLoop { executor.executeDdl(PartitionManager.dropPartitionDdl(partition.name)) }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `a maintenance pass blocked by a parent lock fails and records no success`() {
        assertThat(successRecorded()).describedAs("fresh app, nothing succeeded yet").isEqualTo(0.0)

        val started = System.nanoTime()
        // The failed DDL joins the cluster-lock transaction and marks it rollback-only, so the
        // pass may surface as an exception from maintain() as well as a logged failure: either
        // way the observable contract is the same - no success recorded.
        whileParentIsLocked { runCatching { onEventLoop { maintainer.maintain() } } }
        val waited = Duration.ofNanos(System.nanoTime() - started)

        assertThat(waited)
            .describedAs("the pass must have waited out the 5s lock timeout, and not hung")
            .isGreaterThanOrEqualTo(Duration.ofSeconds(4))
            .isLessThan(Duration.ofSeconds(30))

        assertThat(successRecorded())
            .describedAs("a pass that could not take the parent lock must not record success")
            .isEqualTo(0.0)
    }
}
