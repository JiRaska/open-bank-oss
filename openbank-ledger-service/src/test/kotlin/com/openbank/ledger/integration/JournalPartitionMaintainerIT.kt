// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.ledger.integration

import com.openbank.ledger.infrastructure.partition.HibernatePartitionExecutor
import com.openbank.libs.persistence.partition.PartitionMaintenance
import com.openbank.libs.persistence.partition.PartitionManager
import com.openbank.libs.persistence.partition.PartitionPolicy
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
import java.time.LocalDate

/**
 * Exercises the real reactive DDL path (Hibernate native-query CREATE / DETACH / DROP +
 * pg_inherits listing + audit insert) against the dedicated ledger IT database. This is the one
 * thing the libs-side unit tests cannot cover — that `createNativeQuery(ddl).executeUpdate()`
 * actually runs partition DDL through the Vert.x reactive Postgres client.
 *
 * The [HibernatePartitionExecutor] is reactive (`Panache.withSession/withTransaction`), so its
 * suspend calls MUST run on a Vert.x duplicated context — a plain `runBlocking` test thread has
 * none and fails with "No current Vertx context found". [onVertxContext] bridges the suspend body
 * onto a Vert.x context via [VertxContextSupport.subscribeAndAwait] and blocks for the result.
 *
 * Each test declares an explicit `: Unit` return — a Kotlin/JUnit5 footgun is that `fun x() = expr`
 * inferring a non-`Unit` type (e.g. an AssertJ assert) makes JUnit5 silently SKIP the test.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.ledger.it.PostgresRedpandaTestResource::class)
class JournalPartitionMaintainerIT {

    @Inject
    lateinit var executor: HibernatePartitionExecutor

    private val parent = "journal_entries"

    // Run a reactive suspend body on a fresh Vert.x duplicated context and block for its result,
    // so Panache.withSession/withTransaction find the context they require.
    private fun <T> onVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    @Test
    fun `V5 migration pre-created the partition horizon through 2028`(): Unit = onVertxContext {
        val children = executor.listChildPartitions(parent)
        assertThat(children).contains(
            "journal_entries_2024",
            "journal_entries_2025",
            "journal_entries_2026",
            "journal_entries_2027",
            "journal_entries_2028",
            "journal_entries_default",
        )
    }

    @Test
    fun `dry-run maintenance runs the full reactive path and records audit rows`(): Unit = onVertxContext {
        val policy = PartitionPolicy(
            parentTable = parent,
            prefix = parent,
            futureYears = 2,
            retentionYears = 10,
            dropEnabled = false,
            dryRun = true,
        )
        // today in 2026 -> roll-forward CREATE 2027/2028 (idempotent, already present), no detach.
        val report = PartitionMaintenance.maintain(LocalDate.of(2026, 5, 29), policy, executor)

        assertThat(report.defaultPartitionRows).isGreaterThanOrEqualTo(0)
        // recordAudit executed for every planned action -> at least the CREATE audits landed.
        assertThat(executor.rowCount("partition_lifecycle_audit")).isGreaterThan(0)
    }

    @Test
    fun `non-dry-run retention detaches an expired partition via real DDL`(): Unit = onVertxContext {
        // Arrange: create a synthetic out-of-window partition for 2015.
        val p2015 = PartitionManager.yearlyPartition(parent, 2015)
        executor.executeDdl(PartitionManager.createPartitionDdl(parent, p2015))
        assertThat(executor.listChildPartitions(parent)).contains("journal_entries_2015")

        // Act: retention window 10y as of 2026 -> 2015 is expired -> DETACH (non-dry-run).
        val policy = PartitionPolicy(
            parentTable = parent,
            prefix = parent,
            futureYears = 0,
            retentionYears = 10,
            dropEnabled = false,
            dryRun = false,
        )
        PartitionMaintenance.maintain(LocalDate.of(2026, 5, 29), policy, executor)

        // Assert: detached -> no longer a child partition, but the table still exists (non-destructive).
        assertThat(executor.listChildPartitions(parent)).doesNotContain("journal_entries_2015")
        assertThat(executor.rowCount("journal_entries_2015")).isEqualTo(0)

        // Cleanup the now-standalone table so reruns stay deterministic.
        executor.executeDdl(PartitionManager.dropPartitionDdl("journal_entries_2015"))
    }
}
