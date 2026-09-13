// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.infrastructure.retention

import com.openbank.copilot.application.port.out.ConversationStore
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Turns copilot's 90-day conversation TTL from a read filter into an actual retention guarantee
 * (#3870, ADR-0118 §5 / ADR-0238).
 *
 * `PostgresConversationStore.load` filters on `expires_at > now()`, so an expired conversation stops
 * being *readable* — but nothing deleted the row, and the message text (free-text chat, i.e. the
 * least predictable personal data this platform holds) stayed on disk and in every base backup
 * indefinitely. This sweep hard-deletes those rows.
 *
 * ## `suspend fun` is load-bearing, not style
 *
 * A plain `@Scheduled` method is invoked by Quarkus on a bare `executor-thread` with **no Vert.x
 * context**, so the reactive Panache delete would throw `HR000068` before any per-item `try/catch`
 * and the tick would abort having done nothing, silently — the defect that left five schedulers in
 * this repo never running (#2148, #2187). A `suspend` method is dispatched on a duplicated Vert.x
 * context instead. `VertxContextSupport.subscribeAndAwait` is *not* the alternative: it throws when
 * called from an event loop.
 *
 * Runs daily at 03:30 UTC by default; `concurrentExecution = SKIP` prevents overlap on a slow run.
 * [enabled] defaults to **true**: unlike a brand-new retention policy, deleting past-`expires_at`
 * rows only removes conversations the service already refuses to serve, so the sweep cannot destroy
 * anything reachable through the API.
 *
 * ## Liveness heartbeat (ADR-0237)
 *
 * The sweep swallows a failed tick so one bad run cannot kill the schedule — which means a
 * permanently broken sweep is indistinguishable from a clean one from the outside: no exception
 * escapes, no metric moves, and "no conversations deleted" is what a healthy quiet day looks like
 * too. [DomainMetrics.registerWorkflowLiveness] publishes the last-success age so the staleness
 * rule and `openbank-control-liveness-sentinel` can see a schedule that stopped succeeding.
 * [recordSuccess] is called only after a delete actually returned, never in the `catch` and never
 * on the disabled short-circuit — a heartbeat on the failure path would assert exactly the thing
 * it exists to disprove.
 *
 * Registration hangs off [StartupEvent], not `@PostConstruct`: `@ApplicationScoped` is lazy, so a
 * `@PostConstruct` here would first run when the cron first fires — up to a day after boot, leaving
 * the gauge absent for that whole window, and absent is not the same signal as stale.
 */
@ApplicationScoped
class ConversationRetentionScheduler(
    private val conversationStore: ConversationStore,
    private val clock: Clock,
    @ConfigProperty(name = "copilot.retention.conversation.enabled", defaultValue = "true")
    private val enabled: Boolean,
    private val domainMetrics: DomainMetrics,
) {

    private val log = Logger.getLogger(ConversationRetentionScheduler::class.java)

    private var liveness: WorkflowLivenessRecorder? = null

    fun registerLiveness(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    @Suppress("TooGenericExceptionCaught") // one failed tick must not kill the schedule
    @Scheduled(
        cron = "{copilot.retention.conversation.cron:0 30 3 * * ?}",
        concurrentExecution = SKIP,
    )
    suspend fun sweepExpiredConversations() {
        if (!enabled) return
        val now = Instant.now(clock)
        // observed-by: the workflow-liveness gauge. recordSuccess() runs only on a completed sweep,
        // so a failing tick leaves openbank_workflow_last_success_age_seconds climbing until
        // WorkflowLivenessStale fires (ADR-0237). That is a real signal for a job with no message to
        // dead-letter, whose next tick retries the same work anyway — unlike a consumer, where
        // acking discards the event. Stated because a bare catch here is indistinguishable from the
        // #5698 defect.
        try {
            val deleted = conversationStore.deleteExpired(now)
            liveness?.recordSuccess()
            if (deleted > 0) {
                log.infof(
                    "[retention] Hard-deleted %d copilot conversation(s) with expires_at <= %s",
                    deleted,
                    now,
                )
            }
        } catch (e: Exception) {
            log.errorf(e, "[retention] copilot conversation retention sweep failed at %s", now)
        }
    }

    private companion object {
        const val WORKFLOW_NAME = "copilot-conversation-retention"
        val EXPECTED_INTERVAL: Duration = Duration.ofDays(1)
    }
}
