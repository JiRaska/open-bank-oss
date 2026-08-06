// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.infrastructure.retention

import com.openbank.copilot.application.port.out.ConversationStore
import io.quarkus.scheduler.Scheduled
import io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
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
 */
@ApplicationScoped
class ConversationRetentionScheduler(
    private val conversationStore: ConversationStore,
    private val clock: Clock,
    @ConfigProperty(name = "copilot.retention.conversation.enabled", defaultValue = "true")
    private val enabled: Boolean,
) {

    private val log = Logger.getLogger(ConversationRetentionScheduler::class.java)

    @Suppress("TooGenericExceptionCaught") // one failed tick must not kill the schedule
    @Scheduled(
        cron = "{copilot.retention.conversation.cron:0 30 3 * * ?}",
        concurrentExecution = SKIP,
    )
    suspend fun sweepExpiredConversations() {
        if (!enabled) return
        val now = Instant.now(clock)
        try {
            val deleted = conversationStore.deleteExpired(now)
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
}
