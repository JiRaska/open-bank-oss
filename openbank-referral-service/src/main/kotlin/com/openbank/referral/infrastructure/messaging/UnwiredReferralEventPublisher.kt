// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.referral.infrastructure.messaging

import com.openbank.referral.application.port.out.ReferralEventPublisher
import com.openbank.referral.domain.ReferralEvent
import com.openbank.referral.domain.ReferralPublishOutcome
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.jboss.logging.Logger

/**
 * The referral slice ships WITHOUT an event transport: `openbank-contracts/openbank-referral-service/asyncapi.yaml`
 * declares `referral.qualified.v1` and `referral.reward.requested.v1`, and nothing in this build
 * produces to them.
 *
 * The previous adapter expressed that as an empty method body, which is the one shape this fleet
 * has already been burned by: a money-path no-op that is indistinguishable from a working
 * publisher. Every reward event was dropped with no metric, no log and no distinguishable return
 * value, so no telemetry anywhere could disagree with "events are flowing".
 *
 * This adapter keeps the same (absent) behaviour and makes it LOUD instead:
 *  - a distinct outcome, [ReferralPublishOutcome.TRANSPORT_NOT_WIRED], that no delivering adapter
 *    can ever return;
 *  - a boot-time WARN — `@Startup` because `@ApplicationScoped` is lazy and a constructor warning
 *    on a rarely-called bean can otherwise never appear in a pod log;
 *  - a per-event WARN carrying the event type and id, so a dropped reward is recoverable from logs;
 *  - a counter named for what is actually true. There is deliberately NO `published`/`sent`
 *    counter in this class: the only series it emits is the dropped one, so
 *    `openbank_referral_events_dropped_total > 0` is the alertable signal, and its ABSENCE cannot
 *    be read as success either (nothing here ever reports success).
 *
 * Replace this bean with a real outbox->Kafka adapter when the event-schema slice lands; note
 * `openbank.outbox.dispatch-enabled` defaults to `false` and must be set `true` in
 * `application.yaml`, or the outbox rows are written and never dispatched, with no error.
 */
@Startup
@ApplicationScoped
class UnwiredReferralEventPublisher : ReferralEventPublisher {

    @Inject
    lateinit var registryInstance: Instance<MeterRegistry>

    private fun registry(): MeterRegistry? = if (registryInstance.isResolvable) registryInstance.get() else null

    @PostConstruct
    fun warnAtStartup() {
        LOG.warnf(
            "Referral event transport is NOT WIRED: channels %s are declared in asyncapi.yaml and " +
                "have no producer in this build. Every referral event is DROPPED and counted as " +
                "%s{reason=%s}. No downstream consumer will observe referral rewards.",
            DECLARED_CHANNELS.joinToString(", "),
            DROPPED_COUNTER,
            REASON,
        )
    }

    override suspend fun publish(event: ReferralEvent): ReferralPublishOutcome {
        LOG.warnf(
            "DROPPING referral event type=%s eventId=%s programId=%s inviteId=%s occurredAt=%s: %s",
            event.eventType,
            event.eventId,
            event.programId,
            event.inviteId,
            event.occurredAt,
            "no transport is wired in this build",
        )
        registry()?.counter(DROPPED_COUNTER, "event_type", event.eventType, "reason", REASON)?.increment()
        return ReferralPublishOutcome.TRANSPORT_NOT_WIRED
    }

    companion object {
        private val LOG: Logger = Logger.getLogger(UnwiredReferralEventPublisher::class.java)
        const val DROPPED_COUNTER = "openbank_referral_events_dropped_total"
        const val REASON = "transport_not_wired"
        val DECLARED_CHANNELS = listOf("referral.qualified.v1", "referral.reward.requested.v1")
    }
}
