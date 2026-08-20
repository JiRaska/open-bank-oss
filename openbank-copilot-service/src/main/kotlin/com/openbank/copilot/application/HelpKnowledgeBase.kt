// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.application

import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/**
 * In-process keyword retrieval over a bundled, curated **customer help** corpus (ADR-0089 D4
 * grounding). No embeddings, no vector store, no external infra — deterministic and testable, and
 * cheap (FinOps, ADR-0027). Docs live under `src/main/resources/help/` (markdown), listed in index.txt.
 *
 * "How do I…" answers are grounded in these passages WITH citations, instead of the model's
 * parametric memory. The corpus is customer-facing help only — never internal ADRs or runbooks.
 */
@Startup
@ApplicationScoped
class HelpKnowledgeBase : com.openbank.copilot.application.port.out.CorpusSource {

    data class Passage(val docTitle: String, val source: String, val text: String)

    /**
     * A corpus passage with the identity the derived vector index needs (ADR-0183): a deterministic
     * [chunkId] so re-indexing rewrites rows instead of accumulating them, and a [contentHash] so an
     * unchanged corpus costs zero embedding calls.
     */
    data class Chunk(
        val chunkId: String,
        val source: String,
        val docTitle: String,
        val ordinal: Int,
        val content: String,
        val contentHash: String,
    )

    data class Hit(val passage: Passage, val score: Double)

    private val log = Logger.getLogger(HelpKnowledgeBase::class.java)
    private val passages = mutableListOf<Passage>()

    @PostConstruct
    fun load() {
        val cl = Thread.currentThread().contextClassLoader
        val names = cl.getResourceAsStream("help/index.txt")?.bufferedReader()?.readLines().orEmpty()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        for (name in names) {
            val raw = cl.getResourceAsStream("help/$name")?.bufferedReader()?.readText() ?: continue
            val title = raw.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim() ?: name
            raw.split(PARAGRAPH).map { it.trim() }.filter { it.length >= MIN_CHUNK }.forEach {
                passages += Passage(title, "help/$name", it)
            }
        }
        log.infof("help knowledge base loaded: %d passages from %d docs", passages.size, names.size)
    }

    fun search(query: String, k: Int = TOP_K): List<Hit> = rank(query, passages, k)

    /**
     * The same passages, keyed for the vector index. Derived from the loaded corpus rather than
     * re-read from the classpath, so the index and the answers can never be built from two
     * different readings of the same resources.
     */
    override fun chunks(): List<Chunk> {
        var ordinal = 0
        return passages.map { p ->
            val n = ordinal++
            val hash = sha256(p.text)
            Chunk(
                // Identity is (source, ordinal), NOT the content hash: keying by hash would make an
                // edited paragraph a new row and leave the old one orphaned until the prune, and the
                // prune is exactly the step that must not be load-bearing.
                chunkId = sha256("${p.source}#$n"),
                source = p.source,
                docTitle = p.docTitle,
                ordinal = n,
                content = p.text,
                contentHash = hash,
            )
        }
    }

    companion object {
        const val MIN_CHUNK = 40
        const val MIN_TERM = 3
        const val TOP_K = 3
        private val PARAGRAPH = Regex("\\n\\s*\\n")
        private val NON_WORD = Regex("[^\\p{L}\\p{Nd}]+")

        /** Pure, deterministic ranking: fraction of query terms present in each passage. */
        fun rank(query: String, passages: List<Passage>, k: Int = TOP_K): List<Hit> {
            val q = terms(query)
            if (q.isEmpty()) return emptyList()
            return passages
                .map { p -> Hit(p, terms(p.text).let { pt -> q.count { it in pt }.toDouble() / q.size }) }
                .filter { it.score > 0.0 }
                .sortedByDescending { it.score }
                .take(k)
        }

        fun sha256(s: String): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

        private fun terms(s: String): Set<String> =
            s.lowercase().split(NON_WORD).filter { it.length >= MIN_TERM }.toSet()
    }
}
