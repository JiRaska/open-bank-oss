// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.infrastructure.persistence

import com.openbank.copilot.application.port.out.PassageIndex
import io.quarkus.arc.properties.IfBuildProperty
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.sqlclient.Pool
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger

/**
 * pgvector-backed [PassageIndex] (ADR-0183, ADR-0265 slice 4).
 *
 * Raw SQL over the Vert.x pool rather than Panache: `vector` is not a Hibernate type here, the
 * queries are two statements, and the nearest-neighbour operator (`<=>`) has no ORM expression —
 * mapping it would be more code than the SQL it hides.
 *
 * The vector is passed as a **string literal cast to `vector`** (`$3::vector`), which is pgvector's
 * documented text input format. It is not string interpolation into the statement: the value is a
 * bind parameter like any other, so there is no injection surface, and the ASVS V10.1 gate's
 * "parameterised SQL only" rule is satisfied by construction.
 */
@ApplicationScoped
@IfBuildProperty(name = "copilot.retrieval.vector-store", stringValue = "postgres", enableIfMissing = true)
class PgVectorPassageIndex : PassageIndex {

    @Inject
    lateinit var client: Pool

    private val log = Logger.getLogger(PgVectorPassageIndex::class.java)

    override suspend fun contentHashes(): Map<String, String> =
        client.query("SELECT chunk_id, content_hash FROM help_passage_embedding").execute()
            .awaitSuspending()
            .associate { it.getString("chunk_id") to it.getString("content_hash") }

    override suspend fun upsert(rows: List<PassageIndex.IndexedPassage>) {
        for (row in rows) {
            client.preparedQuery(UPSERT_SQL).execute(
                Tuple.of(row.chunkId, row.source, row.docTitle)
                    .addInteger(row.ordinal)
                    .addString(row.content)
                    .addString(row.contentHash)
                    .addString(row.model)
                    .addString(literal(row.embedding)),
            ).awaitSuspending()
        }
    }

    override suspend fun deleteMissing(keepChunkIds: Set<String>): Int {
        // An empty keep-set means "the corpus produced no chunks", which is a bug in the caller or a
        // broken resource bundle — never a licence to wipe the index. Deleting everything here would
        // turn a load failure into an outage that survives the next restart.
        if (keepChunkIds.isEmpty()) {
            log.warn("refusing to prune the passage index against an empty keep-set")
            return 0
        }
        return client.preparedQuery("DELETE FROM help_passage_embedding WHERE chunk_id <> ALL($1)")
            .execute(Tuple.of(keepChunkIds.toTypedArray()))
            .awaitSuspending()
            .rowCount()
    }

    override suspend fun search(embedding: FloatArray, model: String, k: Int): List<PassageIndex.Match> =
        client.preparedQuery(SEARCH_SQL)
            .execute(Tuple.of(literal(embedding), model, k))
            .awaitSuspending()
            .map {
                PassageIndex.Match(
                    source = it.getString("source"),
                    docTitle = it.getString("doc_title"),
                    content = it.getString("content"),
                    // `<=>` is cosine DISTANCE (0 = identical). Similarity is 1 - distance, and the
                    // conversion happens here so no caller has to remember which direction is better.
                    similarity = 1.0 - it.getDouble("distance"),
                )
            }

    private fun literal(v: FloatArray): String = v.joinToString(prefix = "[", postfix = "]", separator = ",")

    private companion object {
        const val UPSERT_SQL = """
            INSERT INTO help_passage_embedding
                (chunk_id, source, doc_title, ordinal, content, content_hash, model, embedding, updated_at)
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8::vector, now())
            ON CONFLICT (chunk_id) DO UPDATE SET
                source = EXCLUDED.source,
                doc_title = EXCLUDED.doc_title,
                ordinal = EXCLUDED.ordinal,
                content = EXCLUDED.content,
                content_hash = EXCLUDED.content_hash,
                model = EXCLUDED.model,
                embedding = EXCLUDED.embedding,
                updated_at = now()
        """

        // Filtered by model: vectors from two different models are not comparable, so a half-migrated
        // index must return the rows of the CURRENT model only rather than silently mixing distances
        // from two spaces — which produces plausible-looking, wrong neighbours.
        const val SEARCH_SQL = """
            SELECT source, doc_title, content, embedding <=> $1::vector AS distance
            FROM help_passage_embedding
            WHERE model = $2
            ORDER BY embedding <=> $1::vector
            LIMIT $3
        """
    }
}
