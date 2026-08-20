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
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.security.MessageDigest
import java.time.Duration

/**
 * Keeps the pgvector index in step with the bundled help corpus (ADR-0183 §2: the markdown is the
 * source of truth, the embeddings are a derived index).
 *
 * ## Why a scheduler and not a one-shot at startup
 *
 * The corpus changes on deploy, so a startup run would cover it — but a startup run also has to
 * succeed on the very first boot of a pod whose gateway or database may not be reachable yet, and a
 * failed one-shot never retries. A cron that re-checks is idempotent by construction: it compares
 * content hashes and does nothing when nothing changed, so the steady-state cost is one cheap
 * query.
 *
 * **`suspend fun`, not `fun` + `runBlocking`.** A plain `@Scheduled` method carries no Vert.x
 * context, so a reactive database call inside `runBlocking` throws HR000068 and the job aborts
 * having done nothing — silently, because the throw lands before any per-item catch. Five schedulers
 * in this fleet had never run for exactly that reason (#2148, #2187); it is now a hard rule
 * (`rules.yaml: scheduled_methods`) with a CI guard.
 *
 * ## Liveness heartbeat (ADR-0237)
 *
 * A job whose steady state is "did nothing, because nothing changed" is indistinguishable from one
 * that stopped running: no rows move, no exception escapes, and the retrieval side degrades quietly
 * to keyword-only. The last-success gauge is the only thing that separates those. Registered from
 * [StartupEvent] rather than `@PostConstruct` because `@ApplicationScoped` is lazy — a
 * `@PostConstruct` here would first run when the cron first fires, up to six hours after boot,
 * leaving the gauge ABSENT for that window, and absent is a different signal from stale.
 *
 * `recordSuccess()` is called only on a run that actually completed its work, never on the disabled
 * short-circuit and never after an embedding outage: a heartbeat on those paths would assert
 * exactly the thing it exists to disprove.
 */
@ApplicationScoped
class HelpCorpusIndexer(
    private val corpus: CorpusSource,
    private val embeddings: EmbeddingPort,
    private val index: PassageIndex,
    @ConfigProperty(name = "copilot.retrieval.semantic-enabled", defaultValue = "false")
    private val enabled: Boolean,
    private val domainMetrics: DomainMetrics,
) {

    private val log = Logger.getLogger(HelpCorpusIndexer::class.java)

    private var liveness: WorkflowLivenessRecorder? = null

    fun registerLiveness(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    // `every` + `delayed`, NOT a cron. A cron alone means a freshly deployed environment has an
    // EMPTY index until the next scheduled hour — measured on the sandbox rollout: the table sat at
    // 0 rows with the pod healthy, and every help search silently answered keyword-only for up to
    // six hours. The delay is what makes a boot-time run safe: at t=0 the gateway route, the ESO
    // secret and the database may all still be settling, and a run that fails then simply logs and
    // is retried by the next tick — which is the property a one-shot @Startup would not have.
    //
    // SKIP rather than PROCEED on overlap: the run is idempotent by content hash, but two
    // concurrent passes would embed the same stale chunks twice and pay for it twice.
    @Scheduled(
        every = "{copilot.retrieval.index-interval}",
        delayed = "{copilot.retrieval.index-initial-delay}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun reindex() {
        if (!enabled) return
        val chunks = corpus.chunks()
        if (chunks.isEmpty()) {
            // Never prune on an empty corpus — see PgVectorPassageIndex.deleteMissing. An empty
            // corpus means the resource bundle failed to load, and wiping the index would turn a
            // recoverable load failure into a persistent retrieval outage.
            log.warn("help corpus produced no chunks — skipping reindex entirely")
            return
        }
        val existing = index.contentHashes()
        val stale = chunks.filter { existing[it.chunkId] != it.contentHash }
        if (stale.isEmpty()) {
            log.debugf("help index up to date (%d chunks)", chunks.size)
        } else if (!embedAndStore(stale)) {
            // Embeddings are down. Stop BEFORE the prune: the keep-set is correct, but pruning while
            // the index is knowingly incomplete deletes rows a previous successful run stored and
            // this run could not replace. Caught by a test — the first version of this method let
            // the `return` inside embedAndStore fall through to the prune, which reads as an early
            // exit and is not one.
            return
        }
        val removed = index.deleteMissing(chunks.map { it.chunkId }.toSet())
        if (removed > 0) log.infof("help index: pruned %d chunk(s) no longer in the corpus", removed)
        liveness?.recordSuccess()
    }

    /** @return false when embeddings were unavailable, so the caller must not prune. */
    private suspend fun embedAndStore(stale: List<HelpKnowledgeBase.Chunk>): Boolean {
        var stored = 0
        for (batch in stale.chunked(BATCH)) {
            val vectors = embeddings.embed(batch.map { it.content })
            if (vectors == null) {
                // Partial progress is kept on purpose: whatever was already stored is valid, and the
                // next run picks up where this one stopped. Retrieval degrades to keyword-only for
                // the rest, and reports that it did.
                log.warnf("embedding unavailable — help index left partial (%d of %d stored)", stored, stale.size)
                return false
            }
            index.upsert(
                batch.mapIndexed { i, c ->
                    PassageIndex.IndexedPassage(
                        chunkId = c.chunkId,
                        source = c.source,
                        docTitle = c.docTitle,
                        ordinal = c.ordinal,
                        content = c.content,
                        contentHash = c.contentHash,
                        model = embeddings.model,
                        embedding = vectors[i],
                    )
                },
            )
            stored += batch.size
        }
        log.infof("help index: embedded and stored %d chunk(s) with model %s", stored, embeddings.model)
        return true
    }

    companion object {
        /** One request per batch; small because the whole corpus is a few dozen chunks today. */
        const val BATCH = 16

        const val WORKFLOW_NAME = "copilot-help-index"

        /** Matches the default interval (6h); the ADR-0237 staleness rule fires at twice this. */
        val EXPECTED_INTERVAL: Duration = Duration.ofHours(6)

        fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
