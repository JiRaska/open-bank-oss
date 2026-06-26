// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

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
    fun onOversightEvent(payload: String) {
        if (!enabled) return
        runBlocking { oversightService.sweep("kafka-event") }
    }
}
