// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.communication.domain

/**
 * Keyword-only retrieval over published approved answers, mirroring
 * `openbank-copilot-service`'s `HelpKnowledgeBase.search()` term-overlap scorer — a deliberate,
 * documented-in-place scope cut from ADR-0285 D7's "agent-assist" retrieval: the fleet's existing
 * hybrid (keyword + pgvector semantic, reciprocal rank fusion) retrieval stack lives entirely
 * inside copilot-service's own hexagon (no shared library, no cross-service index — confirmed by
 * reading `HybridHelpRetrieval`/`HelpCorpusIndexer`/`PassageIndex` directly), so reusing it means
 * standing up this service's OWN pgvector table, CNPG `Database` resource and embedding-gateway
 * wiring from scratch. copilot-service's own semantic half is explicitly an OPT-IN layer on top of
 * a keyword-only baseline that is "a first-class supported mode, not a degraded error state" when
 * semantic search is disabled — so a keyword-only playbook search here is not a shortcut but the
 * same fallback tier that service already ships as fully supported. Semantic search over the
 * playbook corpus is a scoped, documented fast-follow (would mirror `EmbeddingProducer` +
 * `PgVectorPassageIndex` + `HybridHelpRetrieval`'s RRF fusion exactly), not a gap invented here.
 */
object PlaybookAnswerSearch {

    data class Hit(val answer: ApprovedAnswer, val score: Double)

    /**
     * Fraction of query terms present in `situation + " " + answer`, case-insensitive,
     * whitespace-tokenised. Returns the top [limit] answers with score > 0, highest first.
     */
    fun search(query: String, answers: List<ApprovedAnswer>, limit: Int = 5): List<Hit> {
        val queryTerms = tokenize(query)
        if (queryTerms.isEmpty()) return emptyList()
        return answers
            .map { answer ->
                val corpusTerms = tokenize("${answer.situation} ${answer.answer}")
                val overlap = queryTerms.count { it in corpusTerms }
                Hit(answer, overlap.toDouble() / queryTerms.size)
            }
            .filter { it.score > 0.0 }
            .sortedByDescending { it.score }
            .take(limit)
    }

    private fun tokenize(text: String): Set<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }.toSet()
}
