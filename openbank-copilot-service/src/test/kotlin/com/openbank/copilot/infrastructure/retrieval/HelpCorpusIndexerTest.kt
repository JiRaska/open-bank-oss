// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.copilot.infrastructure.retrieval

import com.openbank.copilot.application.HelpKnowledgeBase
import com.openbank.copilot.application.port.out.CorpusSource
import com.openbank.copilot.application.port.out.PassageIndex
import com.openbank.libs.llm.EmbeddingPort
import com.openbank.libs.observability.DomainMetrics
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * What the indexer must get right is not "it writes rows" but the three refusals: it must not
 * re-embed an unchanged corpus (cost), must not prune against an empty corpus (a load failure would
 * become a persistent retrieval outage), and must not pretend success when embeddings are down.
 */
class HelpCorpusIndexerTest {

    private class FakeIndex(initial: Map<String, String> = emptyMap()) : PassageIndex {
        val hashes = initial.toMutableMap()
        val upserted = mutableListOf<PassageIndex.IndexedPassage>()
        var pruneCalls = 0
        var lastKeepSet: Set<String> = emptySet()

        override suspend fun contentHashes(): Map<String, String> = hashes
        override suspend fun upsert(rows: List<PassageIndex.IndexedPassage>) {
            upserted += rows
            rows.forEach { hashes[it.chunkId] = it.contentHash }
        }

        override suspend fun deleteMissing(keepChunkIds: Set<String>): Int {
            pruneCalls++
            lastKeepSet = keepChunkIds
            return 0
        }

        override suspend fun search(embedding: FloatArray, model: String, k: Int): List<PassageIndex.Match> =
            emptyList()
    }

    private class FakeEmbeddings(private val available: Boolean = true) : EmbeddingPort {
        override val model = "BAAI/bge-m3"
        override val dimensions = 3
        var calls = 0
        override suspend fun embed(texts: List<String>): List<FloatArray>? {
            calls++
            return if (available) texts.map { floatArrayOf(0.1f, 0.2f, 0.3f) } else null
        }
    }

    private fun corpusOf(chunks: List<HelpKnowledgeBase.Chunk>) = CorpusSource { chunks }

    private fun chunk(id: String, content: String) = HelpKnowledgeBase.Chunk(
        chunkId = id,
        source = "help/x.md",
        docTitle = "X",
        ordinal = 0,
        content = content,
        contentHash = HelpKnowledgeBase.sha256(content),
    )

    @Test
    fun `disabled indexer does nothing at all`(): Unit = runBlocking {
        val index = FakeIndex()
        val emb = FakeEmbeddings()
        HelpCorpusIndexer(
            corpusOf(listOf(chunk("c1", "text"))),
            emb,
            index,
            enabled = false,
            domainMetrics = DomainMetrics(),
        ).reindex()

        assertThat(emb.calls).isZero()
        assertThat(index.pruneCalls).isZero()
    }

    @Test
    fun `an unchanged corpus costs no embedding call`(): Unit = runBlocking {
        val c = chunk("c1", "text")
        val index = FakeIndex(mapOf("c1" to c.contentHash))
        val emb = FakeEmbeddings()

        HelpCorpusIndexer(corpusOf(listOf(c)), emb, index, enabled = true, domainMetrics = DomainMetrics()).reindex()

        assertThat(emb.calls).isZero()
        assertThat(index.upserted).isEmpty()
        // The prune still runs: a chunk deleted from the corpus must leave the index even when
        // nothing else changed.
        assertThat(index.pruneCalls).isEqualTo(1)
    }

    @Test
    fun `only changed chunks are re-embedded`(): Unit = runBlocking {
        val unchanged = chunk("c1", "same")
        val changed = chunk("c2", "new text")
        val index = FakeIndex(mapOf("c1" to unchanged.contentHash, "c2" to "stale-hash"))

        HelpCorpusIndexer(
            corpusOf(listOf(unchanged, changed)),
            FakeEmbeddings(),
            index,
            enabled = true,
            domainMetrics = DomainMetrics(),
        ).reindex()

        assertThat(index.upserted.map { it.chunkId }).containsExactly("c2")
    }

    @Test
    fun `an empty corpus never prunes`(): Unit = runBlocking {
        val index = FakeIndex(mapOf("c1" to "h"))

        HelpCorpusIndexer(
            corpusOf(emptyList()),
            FakeEmbeddings(),
            index,
            enabled = true,
            domainMetrics = DomainMetrics(),
        ).reindex()

        // The load failed; the previously indexed corpus is the best thing available and must
        // survive. Pruning here would turn a recoverable failure into an outage that persists
        // across restarts.
        assertThat(index.pruneCalls).isZero()
    }

    @Test
    fun `an embedding outage leaves the index partial, and does not prune it away`(): Unit = runBlocking {
        val index = FakeIndex()

        HelpCorpusIndexer(
            corpusOf(listOf(chunk("c1", "a"), chunk("c2", "b"))),
            FakeEmbeddings(available = false),
            index,
            enabled = true,
            domainMetrics = DomainMetrics(),
        ).reindex()

        assertThat(index.upserted).isEmpty()
        // Returns early rather than falling through to the prune: pruning with nothing embedded
        // would delete rows a previous successful run had stored.
        assertThat(index.pruneCalls).isZero()
    }

    @Test
    fun `stored rows carry the model that produced them`(): Unit = runBlocking {
        val index = FakeIndex()

        HelpCorpusIndexer(
            corpusOf(listOf(chunk("c1", "a"))),
            FakeEmbeddings(),
            index,
            enabled = true,
            domainMetrics = DomainMetrics(),
        ).reindex()

        assertThat(index.upserted.single().model).isEqualTo("BAAI/bge-m3")
    }
}
