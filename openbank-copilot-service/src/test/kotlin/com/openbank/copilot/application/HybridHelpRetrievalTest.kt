// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.copilot.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Fusion is the one part of hybrid retrieval where a subtle ordering bug can live, and it is pure,
 * so it is tested without a database or a model.
 *
 * The assertions are about ORDER and about which system's ranking wins where — not about the score
 * values, which are an RRF implementation detail no caller reads.
 */
class HybridHelpRetrievalTest {

    private fun hit(source: String, text: String, score: Double) =
        HelpKnowledgeBase.Hit(HelpKnowledgeBase.Passage("Doc", source, text), score)

    @Test
    fun `a passage both systems found outranks one only the top of a single system found`() {
        val keyword = listOf(hit("a.md", "only-keyword", 1.0), hit("b.md", "both", 0.5))
        val semantic = listOf(hit("c.md", "only-semantic", 0.9), hit("b.md", "both", 0.8))

        val fused = HybridHelpRetrieval.fuse(keyword, semantic, k = 3)

        // "both" is rank 2 in each list and still wins: agreement across two independent systems is
        // the signal RRF exists to capture, and it is exactly what neither system can express alone.
        assertThat(fused.map { it.passage.text }).startsWith("both")
        assertThat(fused).hasSize(3)
    }

    @Test
    fun `identical rank profiles preserve the keyword system's order`() {
        val keyword = listOf(hit("a.md", "first", 1.0), hit("b.md", "second", 0.9))
        val semantic = listOf(hit("c.md", "other-first", 0.9), hit("d.md", "other-second", 0.8))

        val fused = HybridHelpRetrieval.fuse(keyword, semantic, k = 4)

        // Ties broken by insertion order, and keyword is inserted first — deliberate: for a query
        // with a literal term (a fee name, a product code) the exact-match system is the one to
        // trust, and a tie must not silently reorder in favour of the fuzzier one.
        assertThat(fused.map { it.passage.text }).containsExactly("first", "other-first", "second", "other-second")
    }

    @Test
    fun `a passage reaching fusion from both paths is not returned twice`() {
        // The same chunk arrives as a classpath passage from the keyword scorer and as a database
        // row from pgvector: different objects, same (source, text). Identity must be by value, or
        // the customer sees the same paragraph twice with two different citations.
        val same = "Kartu zablokujete v aplikaci."
        val fused = HybridHelpRetrieval.fuse(
            listOf(hit("help/ztracena-karta.md", same, 1.0)),
            listOf(hit("help/ztracena-karta.md", same, 0.77)),
            k = 5,
        )

        assertThat(fused).hasSize(1)
    }

    @Test
    fun `k bounds the result`() {
        val many = (1..10).map { hit("d$it.md", "text $it", 1.0 / it) }

        assertThat(HybridHelpRetrieval.fuse(many, emptyList(), k = 3)).hasSize(3)
    }

    @Test
    fun `an empty semantic list leaves the keyword order untouched`() {
        val keyword = (1..3).map { hit("d$it.md", "text $it", 1.0 / it) }

        val fused = HybridHelpRetrieval.fuse(keyword, emptyList(), k = 3)

        assertThat(fused.map { it.passage.text }).containsExactlyElementsOf(keyword.map { it.passage.text })
    }
}
