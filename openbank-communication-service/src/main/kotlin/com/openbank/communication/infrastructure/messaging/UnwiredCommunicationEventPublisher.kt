// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.communication.infrastructure.messaging

import com.openbank.communication.application.port.out.CommunicationEventPublisher
import com.openbank.communication.domain.PersonaPublishOutcome
import com.openbank.communication.domain.PublishedStyle
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.jboss.logging.Logger

/**
 * This slice ships WITHOUT the `communication.persona.published.v1` transport (ADR-0285 D5).
 * Not a correctness gap: consumers refresh their cached style on the event OR a short TTL
 * (D5), so a dropped event only delays a refresh that happens anyway — but a money-path-style
 * no-op sharing a signal with success is exactly the trap this fleet has been burned by before
 * (`openbank-referral-service`'s `UnwiredReferralEventPublisher`, mirrored here verbatim), so
 * the absence is loud rather than silent: a distinct outcome, a boot-time WARN, a per-event WARN,
 * and a dropped-counter with no corresponding "published" counter to misread.
 *
 * Replace with a real outbox -> Kafka adapter (`openbank-libs-runtime`'s
 * `AbstractOutboxDispatcher`) when the event-schema slice lands; remember
 * `openbank.outbox.dispatch-enabled` defaults to `false`.
 */
@Startup
@ApplicationScoped
class UnwiredCommunicationEventPublisher : CommunicationEventPublisher {

    @Inject
    lateinit var registryInstance: Instance<MeterRegistry>

    private fun registry(): MeterRegistry? = if (registryInstance.isResolvable) registryInstance.get() else null

    @PostConstruct
    fun warnAtStartup() {
        LOG.warnf(
            "communication.persona.published.v1 transport is NOT WIRED. Every publish event is " +
                "DROPPED and counted as %s{reason=%s}. Consumers still refresh via GET " +
                "/api/v1/personas/{key}/published on a TTL, so this delays refresh rather than " +
                "hiding data.",
            DROPPED_COUNTER,
            REASON,
        )
    }

    override suspend fun publishPersonaPublished(personaKey: String, published: PublishedStyle): PersonaPublishOutcome {
        LOG.warnf(
            "DROPPING persona.published event personaKey=%s styleVersion=%s: no transport is wired in this build",
            personaKey,
            published.styleVersion,
        )
        registry()?.counter(DROPPED_COUNTER, "persona_key", personaKey, "reason", REASON)?.increment()
        return PersonaPublishOutcome.TRANSPORT_NOT_WIRED
    }

    companion object {
        private val LOG: Logger = Logger.getLogger(UnwiredCommunicationEventPublisher::class.java)
        const val DROPPED_COUNTER = "openbank_communication_events_dropped_total"
        const val REASON = "transport_not_wired"
    }
}
