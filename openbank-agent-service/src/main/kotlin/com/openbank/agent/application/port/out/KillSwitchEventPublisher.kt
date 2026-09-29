// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.application.port.out

import java.time.Instant
import java.util.UUID

/**
 * Outbound port: publishes kill-switch set/clear events so downstream consumers
 * (case-coordinator-agent in ADR-0244) can react to a runtime halt without polling.
 *
 * The wire shape matches [com.openbank.casecoordinator.infrastructure.messaging.AgentKillSwitchEventConsumer]
 * expectations: `eventType` is either `agent.killswitch.set` or `agent.killswitch.cleared`,
 * `aggregateId` is the scope (`*` or an agent id), `actorId`/`reason`/`occurredAt`/`eventId`
 * provide the audit trail.
 *
 * Implementations may be no-ops when Kafka is not configured (local dev/tests), but the port
 * itself is always injected so the service layer does not branch on wiring.
 */
interface KillSwitchEventPublisher {

    /** Publish a kill-switch set/clear event. Idempotent for the same [eventId]. */
    fun publish(event: KillSwitchEvent)
}

/**
 * A kill-switch domain event carried to case-coordinator-agent.
 *
 * The JSON serialization is handled by the infrastructure adapter; this type must expose the
 * exact field names the consumer reads: `eventId`, `eventType`, `aggregateId`, `reason`,
 * `actorId`, `occurredAt`.
 */
data class KillSwitchEvent(
    val eventId: UUID,
    val eventType: String,
    val aggregateId: String,
    val reason: String,
    val actorId: String,
    val occurredAt: Instant,
) {
    companion object {
        const val EVENT_TYPE = "agent-kill-switch-event"
        const val SET = "agent.killswitch.set"
        const val CLEARED = "agent.killswitch.cleared"
    }
}
