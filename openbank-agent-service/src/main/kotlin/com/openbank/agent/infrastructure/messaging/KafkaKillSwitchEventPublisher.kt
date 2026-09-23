// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.agent.application.port.out.KillSwitchEvent
import com.openbank.agent.application.port.out.KillSwitchEventPublisher
import io.smallrye.reactive.messaging.kafka.Record
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter

/**
 * Kafka adapter for [KillSwitchEventPublisher].
 *
 * The channel is resolved lazily via [Instance] so that a disabled SmallRye outgoing connector
 * does not fail service construction in tests or local dev. When disabled, [publish] is a no-op
 * but still returns normally — the runtime halt is already persisted and audited by the caller.
 */
@ApplicationScoped
class KafkaKillSwitchEventPublisher @Inject constructor(
    @Channel("agent-kill-switch-events-out") private val emitter: Instance<Emitter<Record<String, String>>>,
    private val objectMapper: ObjectMapper,
) : KillSwitchEventPublisher {

    override fun publish(event: KillSwitchEvent) {
        if (!emitter.isResolvable) return
        val payload = objectMapper.writeValueAsString(
            mapOf(
                "eventId" to event.eventId.toString(),
                "eventType" to event.eventType,
                "aggregateId" to event.aggregateId,
                "reason" to event.reason,
                "actorId" to event.actorId,
                "occurredAt" to event.occurredAt.toString(),
            ),
        )
        emitter.get().send(Record.of(event.aggregateId, payload))
    }
}
