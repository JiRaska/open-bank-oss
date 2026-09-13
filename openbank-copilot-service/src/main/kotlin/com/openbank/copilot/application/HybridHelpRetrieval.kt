// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.application

import com.openbank.copilot.application.port.out.PassageIndex
import com.openbank.libs.llm.EmbeddingPort
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.jboss.logging.Logger

/**
 * Hybrid retrieval over the help corpus — keyword first, semantic alongside (ADR-0183 §4,
 * ADR-0265 slice 4).
 *
 * ## Why hybrid rather than replacing the keyword scorer
 *
 * ADR-0183 says it explicitly: the existing overlap scorer works, and pgvector augments rather than
 * replaces it. The two fail in opposite directions — keyword matching is exact and misses
 * paraphrase; embeddings generalise and will happily return something topically adjacent and wrong
 * for a query containing a literal term (an account number, a fee name, a product code). Fusing
 * them keeps the exact-match behaviour customers rely on for those.
 *
 * ## Fusion: reciprocal rank, not score addition
 *
 * The two systems produce incomparable numbers — a keyword score is "fraction of query terms
 * present" (0..1, and often exactly 1.0 for several passages), a cosine similarity is a geometric
 * quantity that is rarely below 0.3 for ANY pair of texts in the same language. Adding or averaging
 * them would let cosine's compressed, high-baseline range dominate every ranking while looking like
 * a weighted blend. Reciprocal rank fusion uses only the ORDER each system produced, which is the
 * part both are actually good at.
 *
 * ## Degradation is reported, never silent
 *
 * When embeddings are unavailable (no key, gateway down, empty index) this returns keyword-only
 * results — a real mode, and the only mode that existed before. It increments
 * `openbank.copilot.retrieval{mode="keyword_only"}` when it does. Without that counter "search got
 * worse after the deploy" is unattributable, and an index that never got built looks exactly like
 * one that did.
 */
@ApplicationScoped
class HybridHelpRetrieval(
    private val knowledgeBase: HelpKnowledgeBase,
    private val embeddings: EmbeddingPort,
    private val index: PassageIndex,
) {

    @Inject
    lateinit var registryInstance: Instance<MeterRegistry>

    private val log = Logger.getLogger(HybridHelpRetrieval::class.java)

    /**
     * Top [k] passages for [query]. Never throws: retrieval failing must degrade the answer, not
     * fail the customer's turn.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun search(query: String, k: Int = HelpKnowledgeBase.TOP_K): List<HelpKnowledgeBase.Hit> {
        val keyword = knowledgeBase.search(query, k = k * CANDIDATE_FACTOR)
        val semantic = try {
            semanticSearch(query, k * CANDIDATE_FACTOR)
        } catch (ex: Exception) {
            log.warnf("semantic retrieval failed, falling back to keyword-only: %s", ex.message)
            null
        }
        if (semantic.isNullOrEmpty()) {
            record("keyword_only")
            return keyword.take(k)
        }
        record("hybrid")
        return fuse(keyword, semantic, k)
    }

    private suspend fun semanticSearch(query: String, k: Int): List<HelpKnowledgeBase.Hit>? {
        val vector = embeddings.embed(listOf(query))?.firstOrNull() ?: return null
        return index.search(vector, embeddings.model, k).map {
            HelpKnowledgeBase.Hit(
                HelpKnowledgeBase.Passage(docTitle = it.docTitle, source = it.source, text = it.content),
                score = it.similarity,
            )
        }
    }

    private fun record(mode: String) {
        val registry = if (registryInstance.isResolvable) registryInstance.get() else return
        Counter.builder("openbank.copilot.retrieval")
            .tags("mode", mode)
            .register(registry)
            .increment()
    }

    companion object {
        /** Fetch more candidates per system than requested, so fusion has something to reorder. */
        const val CANDIDATE_FACTOR = 3

        /**
         * RRF's rank-smoothing constant. 60 is the value from the original Cormack et al. paper and
         * the one every implementation defaults to; it is large enough that the top few ranks are
         * close together, so a passage found by BOTH systems outranks one found first by only one.
         */
        const val RRF_K = 60.0

        /**
         * Pure and deterministic, so the fusion can be tested without a database or a model —
         * which is the only part of this class where a subtle ordering bug could live.
         *
         * Passages are identified by (source, text): the same chunk reaches here from two different
         * code paths (classpath scan vs. database row), so object identity is not available.
         */
        fun fuse(
            keyword: List<HelpKnowledgeBase.Hit>,
            semantic: List<HelpKnowledgeBase.Hit>,
            k: Int,
        ): List<HelpKnowledgeBase.Hit> {
            val scores = LinkedHashMap<Pair<String, String>, Double>()
            val byKey = LinkedHashMap<Pair<String, String>, HelpKnowledgeBase.Hit>()
            for (list in listOf(keyword, semantic)) {
                list.forEachIndexed { i, hit ->
                    val key = hit.passage.source to hit.passage.text
                    scores[key] = (scores[key] ?: 0.0) + 1.0 / (RRF_K + i + 1)
                    byKey.putIfAbsent(key, hit)
                }
            }
            return scores.entries
                .sortedByDescending { it.value }
                .take(k)
                .map { (key, score) -> byKey.getValue(key).copy(score = score) }
        }
    }
}
