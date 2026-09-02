// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.it

import com.openbank.copilot.application.port.out.PassageIndex
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
import java.util.UUID

/**
 * The pgvector index against a REAL Postgres with the extension, because nothing smaller can prove
 * any of it. A mocked index would pass with the `<=>` operator spelled wrong, with the HNSW index
 * built for a different operator class, with distance and similarity inverted, or with the migration
 * not applying at all — and all four are silent: the wrong ones return plausible rows in the wrong
 * order, which reads as "semantic search is a bit fuzzy" rather than as a bug.
 *
 * Driven through [VertxContextSupport] because the index uses the reactive client: a plain
 * `@QuarkusTest` thread carries no Vert.x context and `awaitSuspending` would throw
 * `No current Vertx context found`.
 */
@QuarkusTest
@QuarkusTestResource(CopilotPostgresTestResource::class)
class PgVectorPassageIndexIT {

    @Inject
    lateinit var index: PassageIndex

    // A per-test model id, because search() is global over the table and these tests share one
    // container. The model filter is a real production behaviour (vectors from two models are not
    // comparable), so using it for isolation exercises the query rather than working around it.
    private val model = "test-model-" + UUID.randomUUID().toString().take(8)

    /** Must match the `vector(N)` column in V3__help_embeddings.sql. */
    private val dimensions = 1024

    private fun <T> blocking(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni() }

    /**
     * A unit basis vector of the FULL production width. The column is `vector(1024)` and pgvector
     * rejects any other length outright ("expected 1024 dimensions, not 3") — which is the column
     * doing its job, and the reason the adapter checks width before it ever gets here.
     * Basis vectors keep cosine similarity trivial to reason about: identical = 1, orthogonal = 0.
     */
    private fun basis(axis: Int) = FloatArray(dimensions) { if (it == axis) 1f else 0f }

    private fun passage(id: String, text: String, embedding: FloatArray) = PassageIndex.IndexedPassage(
        chunkId = id,
        source = "help/test.md",
        docTitle = "Test",
        ordinal = 0,
        content = text,
        contentHash = "hash-$id",
        model = model,
        embedding = embedding,
    )

    @Test
    fun `nearest neighbour comes back first, and similarity is 1 for an identical vector`() {
        val run = UUID.randomUUID().toString().take(8)
        blocking {
            index.upsert(
                listOf(
                    passage("$run-x", "the x axis", basis(0)),
                    passage("$run-y", "the y axis", basis(1)),
                ),
            )
        }

        val matches = blocking { index.search(basis(0), model, k = 2) }

        assertThat(matches.first().content).isEqualTo("the x axis")
        // 1 - cosine distance. If the adapter ever returns the raw distance instead, this is 0.0 and
        // every ranking that treats "higher is better" silently inverts.
        assertThat(matches.first().similarity).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6))
        assertThat(matches.last().similarity).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-6))
    }

    @Test
    fun `a row from another model is not returned`() {
        val run = UUID.randomUUID().toString().take(8)
        blocking {
            index.upsert(listOf(passage("$run-other", "other space", basis(0)).copy(model = "$model-other")))
        }

        val matches = blocking { index.search(basis(0), model, k = 5) }

        // Vectors from two models are not comparable. Mixing them produces confident, wrong
        // neighbours — the worst failure shape available to a retrieval system.
        assertThat(matches.map { it.content }).doesNotContain("other space")
    }

    @Test
    fun `upsert is idempotent on chunk id and updates content`() {
        val run = UUID.randomUUID().toString().take(8)
        blocking { index.upsert(listOf(passage("$run-a", "first version", basis(0)))) }
        blocking { index.upsert(listOf(passage("$run-a", "second version", basis(0)))) }

        val hashes = blocking { index.contentHashes() }
        val matches = blocking { index.search(basis(0), model, k = 5) }

        assertThat(hashes).containsKey("$run-a")
        assertThat(matches.map { it.content }).contains("second version").doesNotContain("first version")
    }

    @Test
    fun `pruning against an empty keep-set deletes nothing`() {
        val run = UUID.randomUUID().toString().take(8)
        blocking { index.upsert(listOf(passage("$run-keep", "keep me", basis(2)))) }

        val deleted = blocking { index.deleteMissing(emptySet()) }

        assertThat(deleted).isZero()
        assertThat(blocking { index.contentHashes() }).containsKey("$run-keep")
    }
}
