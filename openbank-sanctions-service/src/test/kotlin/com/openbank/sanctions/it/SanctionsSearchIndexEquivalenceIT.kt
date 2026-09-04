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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Real-Postgres cover for #3265: adding the `<%` trigram operator to the screening search must make
 * the query use `idx_entries_search_trgm` **without changing which entries it matches**.
 *
 * Why equivalence is the assertion and speed is not. `word_similarity(q, search_text) >= t` written
 * as a function is not an indexable predicate, so the search was a parallel sequential scan of every
 * active row — 4002 ms over 814,705 rows in sandbox, against 134 ms once the index is reachable, paid
 * on every screen and twice per payment. But this is the sanctions gate on the money path: a rewrite
 * that is faster and matches a *smaller* set would let a listed subject through, and it would do so
 * silently, because a screen that returns no match is indistinguishable from a clean one.
 *
 * `<%` also takes its cutoff from the session GUC `pg_trgm.word_similarity_threshold`, never from a
 * bind parameter — so the equivalence being asserted here is a property of the implementation
 * pinning that GUC per call, not of the operator alone. If the pinning is dropped and the GUC falls
 * back to its 0.6 default while a caller asks for 0.85, these tests are what notices.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class SanctionsSearchIndexEquivalenceIT {

    @Inject
    lateinit var repository: SanctionsEntryRepositoryImpl

    @Inject
    lateinit var pool: PgPool

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private val lists = listOf(SanctionsListType.OFAC_SDN, SanctionsListType.PEP_GLOBAL)

    @BeforeEach
    fun seed() {
        onEventLoop {
            pool.query("DELETE FROM sanctions_entries").execute().awaitSuspending()
            repository.upsertAll(
                listOf(
                    entry("e-1", "Oldrich Vanek"),
                    entry("e-2", "Oldrich Vanicek"),
                    entry("e-3", "Vanek Oldrich"),
                    entry("e-4", "Jan Novak"),
                    entry("e-5", "Vladimir Petrov", SanctionsListType.PEP_GLOBAL),
                    entry("e-6", "Oldriska Vankova"),
                ),
            )
        }
    }

    private fun entry(
        externalId: String,
        primaryName: String,
        listType: SanctionsListType = SanctionsListType.OFAC_SDN,
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

    /** The pre-#3265 predicate: the exact filter, with no operator and therefore no index. */
    private fun legacyMatches(query: String, threshold: Double): List<Pair<String, Double>> = onEventLoop {
        val inClause = lists.joinToString(",") { "'${it.name}'" }
        pool.preparedQuery(
            """
            SELECT external_id, word_similarity($1, search_text) AS s
            FROM sanctions_entries
            WHERE list_type IN ($inClause) AND active = true
              AND word_similarity($1, search_text) >= $2
            ORDER BY s DESC, external_id
            """.trimIndent(),
        ).execute(Tuple.of(query, threshold.toFloat())).awaitSuspending()
            .map { it.getString("external_id") to (it.getDouble("s") ?: 0.0) }
    }

    private fun currentMatches(query: String, threshold: Double): List<Pair<String, Double>> =
        onEventLoop { repository.search(query, lists, threshold, limit = 100) }
            .map { it.entry.externalId!! to it.score }
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })

    /**
     * The load-bearing test. Several thresholds, including ones on either side of the `<%` GUC
     * default of 0.6 — a threshold below the default is exactly where an unpinned GUC would
     * over-narrow and drop true matches.
     */
    @Test
    fun `the indexed predicate matches exactly what the unindexed one matched`() {
        val queries = listOf("oldrich vanek", "vanek", "oldrich", "jan novak", "petrov", "zzz nomatch")
        val thresholds = listOf(0.3, 0.5, 0.65, 0.85, 0.95)

        for (q in queries) {
            for (t in thresholds) {
                assertThat(currentMatches(q, t))
                    .describedAs("query='%s' threshold=%s — a smaller set here is a missed sanctions hit", q, t)
                    .isEqualTo(legacyMatches(q, t))
            }
        }
    }

    /**
     * Equivalence alone would still hold if the operator were quietly a no-op, so pin the reason the
     * change exists — but pin the claim that is actually testable here.
     *
     * The claim is **not** "the planner picks the trigram index". At any row count a test can
     * reasonably seed, a sequential scan really is cheaper and Postgres is right to choose it; an
     * assertion that only becomes true at 814,705 rows does not become true by being written down.
     * The defect was narrower and sharper: `word_similarity(q, search_text) >= t` as a function is
     * not an indexable predicate, so the index was not merely unchosen, it was **unreachable**.
     *
     * `enable_seqscan = off` isolates exactly that. It makes a sequential scan maximally expensive
     * and asks the planner to use an index if one is applicable. The test asserts both directions,
     * so it cannot pass vacuously: the old function-only predicate must still fail to reach
     * `idx_entries_search_trgm`, and the new one with `<%` must reach it.
     */
    @Test
    fun `the operator makes the trigram index reachable and the old predicate did not`() {
        // The probe deliberately carries ONLY the similarity predicate. With `list_type`/`active`
        // in the WHERE the planner satisfies `enable_seqscan = off` using idx_entries_list_active
        // and never has to consider the trigram index at all — the plan then says nothing about
        // whether the predicate is indexable, which is the entire question.
        fun planFor(predicate: String): String = onEventLoop {
            pool.query("SET enable_seqscan = off").execute().awaitSuspending()
            pool.query("SET pg_trgm.word_similarity_threshold = 0.85").execute().awaitSuspending()
            pool.query(
                """
                EXPLAIN SELECT external_id FROM sanctions_entries WHERE $predicate
                """.trimIndent(),
            ).execute().awaitSuspending().joinToString("\n") { it.getString(0) }
        }

        val legacyPlan = planFor("word_similarity('oldrich vanek', search_text) >= 0.85")
        val currentPlan = planFor(
            "'oldrich vanek' <% search_text AND word_similarity('oldrich vanek', search_text) >= 0.85",
        )

        assertThat(legacyPlan)
            .describedAs(
                "known-negative: without the operator the index must be unreachable, or this test " +
                    "proves nothing about the fix. Plan was:\n%s",
                legacyPlan,
            )
            .doesNotContain("idx_entries_search_trgm")
        assertThat(currentPlan)
            .describedAs("plan was:\n%s", currentPlan)
            .contains("idx_entries_search_trgm")
    }
}
