// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.application.port.out

/**
 * The stored side of semantic retrieval: embeddings of the help corpus, and nearest-neighbour
 * lookup over them (ADR-0183).
 *
 * The corpus itself is NOT here. Source of truth stays the versioned markdown in
 * `src/main/resources/help/` (ADR-0183 §2) — this index is derived and regenerable, which is why
 * [deleteMissing] and a content hash exist at all.
 */
interface PassageIndex {

    /** chunk_id → content_hash for every indexed row, so the indexer can embed only what changed. */
    suspend fun contentHashes(): Map<String, String>

    suspend fun upsert(rows: List<IndexedPassage>)

    /** Drop rows whose chunk is no longer in the corpus. Returns the number deleted. */
    suspend fun deleteMissing(keepChunkIds: Set<String>): Int

    /** Nearest neighbours of [embedding], restricted to rows produced by [model]. */
    suspend fun search(embedding: FloatArray, model: String, k: Int): List<Match>

    data class IndexedPassage(
        val chunkId: String,
        val source: String,
        val docTitle: String,
        val ordinal: Int,
        val content: String,
        val contentHash: String,
        val model: String,
        val embedding: FloatArray,
    ) {
        // FloatArray has identity equals/hashCode, which a data class would inherit and silently
        // make every instance unequal. Nothing compares these today; the overrides exist so a future
        // `assertThat(rows).contains(...)` behaves the way its author expects.
        override fun equals(other: Any?): Boolean = this === other ||
            (
                other is IndexedPassage &&
                    chunkId == other.chunkId &&
                    contentHash == other.contentHash &&
                    model == other.model &&
                    embedding.contentEquals(other.embedding)
                )

        override fun hashCode(): Int =
            (chunkId.hashCode() * PRIME + contentHash.hashCode()) * PRIME + embedding.contentHashCode()

        private companion object {
            const val PRIME = 31
        }
    }

    /** @param similarity cosine similarity in [-1, 1]; 1 = identical. Already converted from distance. */
    data class Match(val source: String, val docTitle: String, val content: String, val similarity: Double)
}
