// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.messaging

import com.openbank.agent.application.OversightService
import io.smallrye.common.annotation.Blocking
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Incoming

/**
 * Kafka consumer for compliance-officer oversight sweep triggers (ADR-0031 D9 P2, Refs #703).
 *
 * Any event on [openbank.oversight-triggers] wakes the compliance sweep immediately, in addition
 * to the scheduled 30-minute cadence. Disabled by default ([AGENT_OVERSIGHT_KAFKA_ENABLED=false]);
 * enabled in GitOps once the Strimzi topic and Kafka bootstrap config are wired up.
 */
@ApplicationScoped
class OversightEventConsumer {

    @Inject
    lateinit var oversightService: OversightService

    @ConfigProperty(name = "agent.oversight.enabled", defaultValue = "false")
    var enabled: Boolean = false

    @Incoming("oversight-events")
    @Blocking
    @Suppress("UnusedParameter") // Smallrye @Incoming requires the payload param; content is not needed here
    fun onOversightEvent(payload: String) {
        if (!enabled) return
        runBlocking { oversightService.sweep("kafka-event") }
    }
}
