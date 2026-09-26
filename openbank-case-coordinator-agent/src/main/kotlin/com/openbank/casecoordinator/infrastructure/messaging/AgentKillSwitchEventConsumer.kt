// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

package com.openbank.casecoordinator.infrastructure.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.casecoordinator.application.CaseKillSwitchCancellationService
import com.openbank.casecoordinator.application.port.out.KillSwitchCommand
import com.openbank.casecoordinator.infrastructure.observability.CaseCoordinatorMetricsService
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.eclipse.microprofile.reactive.messaging.Message
import java.time.Instant

@ApplicationScoped
class AgentKillSwitchEventConsumer(
    private val objectMapper: ObjectMapper,
    private val cancellation: CaseKillSwitchCancellationService,
    private val metrics: CaseCoordinatorMetricsService,
) {
    @Incoming("agent-kill-switch-events-in")
    suspend fun consume(message: Message<String>) {
        val node = objectMapper.readTree(message.payload)
        val operation = node.path("eventType").asText()
        if (operation == SET || operation == CLEARED) {
            val scope = node.required("aggregateId").asText()
            cancellation.handle(
                KillSwitchCommand(
                    eventId = node.required("eventId").asText(),
                    operation = operation,
                    scope = scope,
                    reason = node.path("reason").asText(if (operation == CLEARED) "resumed" else ""),
                    setBy = node.required("actorId").asText(),
                    occurredAt = Instant.parse(node.required("occurredAt").asText()),
                ),
            )
        } else {
            metrics.recordKillSwitchEvent(node.path("aggregateId").asText("unknown"), "ignored")
        }
        Uni.createFrom().completionStage(message.ack()).awaitSuspending()
    }

    private companion object {
        const val SET = "agent.killswitch.set"
        const val CLEARED = "agent.killswitch.cleared"
    }
}
