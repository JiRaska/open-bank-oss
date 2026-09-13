// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.it

import com.openbank.sanctions.domain.model.EntityType
import com.openbank.sanctions.domain.model.SanctionsEntry
import com.openbank.sanctions.domain.model.SanctionsListType
import com.openbank.sanctions.infrastructure.persistence.repository.SanctionsEntryRepositoryImpl
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Real-Postgres verification for the #1432 fix (a PEP_GLOBAL refresh wrote ~1.9GB of WAL onto a
 * 2Gi volume by rewriting all 776k rows on every run, unconditionally, twice — once via
 * `deactivateByListType`'s blanket sweep, once via `upsertAll`'s unconditional `DO UPDATE`).
 *
 * Two claims here were verified against a REAL database rather than assumed, because both are
 * exactly the kind of external-behavior detail that is wrong to guess:
 *
 * 1. `V5__create_sanctions_entries.sql` declares `UNIQUE NULLS NOT DISTINCT (list_type,
 *    external_id)` — a full-table constraint, no partial predicate. `upsertAll`'s
 *    `ON CONFLICT (list_type, external_id) WHERE external_id IS NOT NULL` targets a PARTIAL
 *    unique index by that exact predicate. Whether Postgres accepts a full unique constraint as
 *    the arbiter for a partial ON CONFLICT clause is not something to reason out from memory of
 *    the docs — it either compiles against real Postgres or it throws
 *    "no unique or exclusion constraint matching the ON CONFLICT specification". It does compile
 *    (this test would fail immediately at `upsertAll` otherwise) — Postgres accepts a
 *    non-partial unique constraint as an arbiter for an ON CONFLICT clause whose predicate is a
 *    subset condition, because every row satisfying the constraint already satisfies the
 *    predicate; a truly partial *index* is only required when the constraint ITSELF is partial.
 * 2. `deactivateMissing` binds a Kotlin `Array<String>` as a Postgres `text[]` parameter via
 *    vertx-pg-client, for the `= ANY($2)` anti-join. No other repository in the fleet binds an
 *    array parameter this way — confirmed working here rather than assumed.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class SanctionsEntryRepositoryUpsertIT {

    @Inject
    lateinit var repository: SanctionsEntryRepositoryImpl

    @Inject
    lateinit var pool: PgPool

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    @BeforeEach
    fun clearTable() {
        onEventLoop { pool.query("DELETE FROM sanctions_entries").execute().awaitSuspending() }
    }

    private fun entry(
        externalId: String,
        primaryName: String = "Test Entry $externalId",
        listType: SanctionsListType = SanctionsListType.PEP_GLOBAL,
    ) = SanctionsEntry(
        listType = listType,
        externalId = externalId,
        entityType = EntityType.INDIVIDUAL,
        primaryName = primaryName,
        aliases = emptyList(),
        searchText = primaryName.lowercase(),
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    /** Postgres's own tuple-version marker: changes on any UPDATE that writes a new row version. */
    private fun xmin(externalId: String): Long = onEventLoop {
        pool.preparedQuery("SELECT xmin::text::bigint AS x FROM sanctions_entries WHERE external_id = $1")
            .execute(Tuple.of(externalId))
            .awaitSuspending()
            .iterator().next().getLong("x")
    }

    private fun activeFlag(externalId: String): Boolean = onEventLoop {
        pool.preparedQuery("SELECT active FROM sanctions_entries WHERE external_id = $1")
            .execute(Tuple.of(externalId))
            .awaitSuspending()
            .iterator().next().getBoolean("active")
    }

    private fun rowCount(): Long = onEventLoop {
        pool.query("SELECT count(*) AS c FROM sanctions_entries").execute().awaitSuspending()
            .iterator().next().getLong("c")
    }

    // ──── claim 1: the ON CONFLICT clause matches the real V5 constraint ──────

    @Test
    fun `upsertAll inserts against the real UNIQUE NULLS NOT DISTINCT constraint`(): Unit = runBlocking {
        val count = onEventLoop { repository.upsertAll(listOf(entry("pep-1"))) }

        assertThat(count).isEqualTo(1)
        assertThat(rowCount()).isEqualTo(1)
    }

    @Test
    fun `a second upsertAll for the same external_id hits the ON CONFLICT arbiter, not a duplicate-key error`(): Unit =
        runBlocking {
            onEventLoop { repository.upsertAll(listOf(entry("pep-1", primaryName = "Original Name"))) }

            // If Postgres could not infer the arbiter (the theoretical constraint-mismatch risk),
            // this would throw a duplicate-key violation on the unique constraint instead of
            // routing through DO UPDATE.
            onEventLoop { repository.upsertAll(listOf(entry("pep-1", primaryName = "Updated Name"))) }

            assertThat(rowCount()).isEqualTo(1)
        }

    // ──── claim 2 (the actual #1432 fix): unchanged rows are not rewritten ────

    @Test
    fun `a mixed batch reports the true count of rows actually written, not the batch size`(): Unit = runBlocking {
        onEventLoop {
            repository.upsertAll(listOf(entry("unchanged"), entry("will-change", primaryName = "Before")))
        }

        // unchanged: identical content, must be skipped and NOT counted.
        // will-change: content differs, must be written and counted.
        // brand-new: not present before, must be inserted and counted.
        val affected = onEventLoop {
            repository.upsertAll(
                listOf(
                    entry("unchanged"),
                    entry("will-change", primaryName = "After"),
                    entry("brand-new"),
                ),
            )
        }

        assertThat(affected).isEqualTo(2)
    }

    @Test
    fun `re-upserting byte-identical content does not write a new tuple version`(): Unit = runBlocking {
        onEventLoop { repository.upsertAll(listOf(entry("pep-1"))) }
        val xminAfterInsert = xmin("pep-1")

        val affected = onEventLoop { repository.upsertAll(listOf(entry("pep-1"))) }

        assertThat(affected).isZero() // no row was actually written
        assertThat(xmin("pep-1")).isEqualTo(xminAfterInsert) // and Postgres agrees: same tuple version
    }

    @Test
    fun `re-upserting with changed content DOES write a new tuple version`(): Unit = runBlocking {
        onEventLoop { repository.upsertAll(listOf(entry("pep-1", primaryName = "Original Name"))) }
        val xminAfterInsert = xmin("pep-1")

        val affected = onEventLoop { repository.upsertAll(listOf(entry("pep-1", primaryName = "Changed Name"))) }

        assertThat(affected).isEqualTo(1)
        assertThat(xmin("pep-1")).isNotEqualTo(xminAfterInsert)
    }

    @Test
    fun `reactivating a deactivated row always writes, even with identical content`(): Unit = runBlocking {
        onEventLoop { repository.upsertAll(listOf(entry("pep-1"))) }
        onEventLoop { repository.deactivateMissing(SanctionsListType.PEP_GLOBAL, presentExternalIds = emptySet()) }
        assertThat(activeFlag("pep-1")).isFalse()
        val xminWhileInactive = xmin("pep-1")

        // Same content as the original insert — but the row is inactive, so `active IS NOT TRUE`
        // must force the write even though nothing else changed.
        val affected = onEventLoop { repository.upsertAll(listOf(entry("pep-1"))) }

        assertThat(affected).isEqualTo(1)
        assertThat(activeFlag("pep-1")).isTrue()
        assertThat(xmin("pep-1")).isNotEqualTo(xminWhileInactive)
    }

    // ──── claim 3: the array-parameter anti-join in deactivateMissing works ───

    @Test
    fun `deactivateMissing deactivates only rows absent from the present set`(): Unit = runBlocking {
        onEventLoop { repository.upsertAll(listOf(entry("pep-1"), entry("pep-2"), entry("pep-3"))) }

        val deactivated = onEventLoop {
            repository.deactivateMissing(SanctionsListType.PEP_GLOBAL, presentExternalIds = setOf("pep-1", "pep-3"))
        }

        assertThat(deactivated).isEqualTo(1)
        assertThat(activeFlag("pep-1")).isTrue()
        assertThat(activeFlag("pep-2")).isFalse()
        assertThat(activeFlag("pep-3")).isTrue()
    }

    @Test
    fun `deactivateMissing with an empty present set deactivates everything for that list type`(): Unit = runBlocking {
        onEventLoop { repository.upsertAll(listOf(entry("pep-1"), entry("pep-2"))) }

        val deactivated = onEventLoop {
            repository.deactivateMissing(SanctionsListType.PEP_GLOBAL, presentExternalIds = emptySet())
        }

        assertThat(deactivated).isEqualTo(2)
        assertThat(activeFlag("pep-1")).isFalse()
        assertThat(activeFlag("pep-2")).isFalse()
    }

    @Test
    fun `deactivateMissing does not touch a different list type`(): Unit = runBlocking {
        onEventLoop {
            repository.upsertAll(
                listOf(entry("shared-1", listType = SanctionsListType.PEP_GLOBAL)),
            )
            repository.upsertAll(
                listOf(entry("shared-1", listType = SanctionsListType.EU_CONSOLIDATED)),
            )
        }

        onEventLoop { repository.deactivateMissing(SanctionsListType.PEP_GLOBAL, presentExternalIds = emptySet()) }

        val euActive = onEventLoop {
            pool.preparedQuery("SELECT active FROM sanctions_entries WHERE external_id = $1 AND list_type = $2")
                .execute(Tuple.of("shared-1", SanctionsListType.EU_CONSOLIDATED.name))
                .awaitSuspending()
                .iterator().next().getBoolean("active")
        }
        assertThat(euActive).isTrue()
    }

    @Test
    fun `re-running deactivateMissing with the same present set does not rewrite already-inactive rows`(): Unit =
        runBlocking {
            onEventLoop { repository.upsertAll(listOf(entry("pep-1"))) }
            onEventLoop { repository.deactivateMissing(SanctionsListType.PEP_GLOBAL, presentExternalIds = emptySet()) }
            val xminAfterFirstDeactivation = xmin("pep-1")

            // The `active = true` guard in the WHERE clause must exclude already-inactive rows
            // from being touched again — otherwise every reconciliation pass rewrites the whole
            // inactive tail forever, the same class of waste this fix removes from the other side.
            val deactivated = onEventLoop {
                repository.deactivateMissing(SanctionsListType.PEP_GLOBAL, presentExternalIds = emptySet())
            }

            assertThat(deactivated).isZero()
            assertThat(xmin("pep-1")).isEqualTo(xminAfterFirstDeactivation)
        }
}
