// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.copilot.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The chunk id is the index's PRIMARY KEY, so a duplicate does not fail — it overwrites, and the
 * indexer still reports how many chunks it embedded. That is exactly how this shipped: the log said
 * "embedded and stored 13 chunk(s)" while the table held ONE row, because a mis-escaped template
 * made the id a constant string for every passage.
 *
 * Asserting distinctness is therefore not a style check: it is the only place the difference between
 * "13 stored" and "13 stored under one key" is visible without querying the database.
 */
class HelpKnowledgeBaseChunksTest {

    private fun loaded(): HelpKnowledgeBase = HelpKnowledgeBase().also { it.load() }

    @Test
    fun `every chunk gets its own id`() {
        val chunks = loaded().chunks()

        assertThat(chunks).isNotEmpty
        assertThat(chunks.map { it.chunkId }).doesNotHaveDuplicates()
        assertThat(chunks.map { it.chunkId }.toSet()).hasSameSizeAs(chunks)
    }

    @Test
    fun `the id is derived from source and ordinal, so it is stable across runs`() {
        // Stability is what makes re-indexing an upsert rather than an append: the same corpus must
        // produce the same keys, or every run would orphan the previous run's rows until the prune.
        val first = loaded().chunks().map { it.chunkId }
        val second = loaded().chunks().map { it.chunkId }

        assertThat(second).isEqualTo(first)
    }

    @Test
    fun `chunks carry their content and a hash of it`() {
        val chunks = loaded().chunks()

        assertThat(chunks).allSatisfy {
            assertThat(it.content).isNotBlank()
            assertThat(it.contentHash).isEqualTo(HelpKnowledgeBase.sha256(it.content))
            assertThat(it.source).startsWith("help/")
        }
        // Distinct content must not collapse into one hash either — the indexer skips re-embedding
        // on an unchanged hash, so a constant hash would freeze the index after the first run.
        assertThat(chunks.map { it.contentHash }).doesNotHaveDuplicates()
    }
}
