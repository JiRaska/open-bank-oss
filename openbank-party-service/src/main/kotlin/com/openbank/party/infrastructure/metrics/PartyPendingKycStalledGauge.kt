// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.metrics

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.party.application.port.out.PartyRepository
import io.quarkus.runtime.Startup
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * Publishes `openbank.onboarding.stalled{stage="party-pending-kyc"}` — parties that entered
 * `PENDING_KYC` and never left it (#5698).
 *
 * WHAT THIS IS FOR, AND WHY IT IS A CENSUS
 *   On 2026-08-19 `kyc-db` was unreachable for a few seconds. One `PARTY_CREATED` arrived in that
 *   window; the kyc consumer logged the failure and returned, which ACKED the message. No case was
 *   opened, so the party stayed `PENDING_KYC`, its accounts stayed `PENDING_ACTIVATION`, and the
 *   welcome bonus never ran. **Ten of 73 sandbox parties were in that state, the oldest for ten
 *   weeks, and nothing anywhere reported it** — it surfaced when a human said an account did not
 *   work.
 *
 *   Nothing could have reported it, because a swallowed failure leaves no error to count: no
 *   exception escapes, consumer lag is zero, the DLQ is empty, and no request failed. The only
 *   observable is the party still sitting in the stage — so the signal has to be a periodic census
 *   of the state itself, not a rate.
 *
 * WHY AGE, NOT A RAW COUNT
 *   `PENDING_KYC` is the correct state for a party between creation and its KYC verdict, so a raw
 *   count alarms on healthy traffic. Only parties older than [stallAfter] are counted; anything
 *   younger is normal onboarding in flight.
 *
 * WHY THIS AND NOT A PARTY↔KYC RECONCILER
 *   A reconciler comparing parties against kyc cases would catch only the missing-case cause, and
 *   would need a cross-service read to do it. Counting the symptom catches every cause with no new
 *   trust boundary: the lost event, kyc-service unreachable, or a case opened and then stuck
 *   waiting on a reviewer. What an operator needs to know is "somebody's onboarding is stuck",
 *   which is the same alert in all three cases.
 *
 * The gauge is refreshed on a schedule rather than computed inside the Micrometer callback: the
 * scrape must not block on a database round-trip, and the query is a single indexed COUNT.
 */
@Startup
@ApplicationScoped
class PartyPendingKycStalledGauge {

    private lateinit var parties: PartyRepository
    private lateinit var metrics: DomainMetrics
    private lateinit var clock: Clock
    private lateinit var stallAfter: Duration

    private val log = Logger.getLogger(PartyPendingKycStalledGauge::class.java)
    private val stalled = AtomicLong(0)
    private var liveness: WorkflowLivenessRecorder? = null

    @Inject
    constructor(
        parties: PartyRepository,
        metrics: DomainMetrics,
        clock: Clock,
        @ConfigProperty(name = "openbank.party.pending-kyc.stall-after", defaultValue = "PT24H")
        stallAfter: Duration,
    ) {
        this.parties = parties
        this.metrics = metrics
        this.clock = clock
        this.stallAfter = stallAfter
    }

    // Required by Quarkus CDI for proxy subclass generation — never called at runtime.
    @Suppress("ProtectedMemberInFinalClass")
    protected constructor()

    @PostConstruct
    fun register() = metrics.registerOnboardingStalled(STAGE) { stalled.get() }

    /**
     * The ADR-0237 heartbeat for this job — who watches the watcher.
     *
     * Without it, this detector has the very failure mode it exists to catch: if the scheduler
     * never fires (the `HR000068` Vert.x-context class, a disabled scheduler, a wedged tick), the
     * gauge simply keeps serving its last value. A frozen `0` is indistinguishable from a genuinely
     * healthy zero, so the alert stays quiet and the stalled parties are invisible again. The
     * liveness gauge is seeded at registration, so a job that has never run is loud from boot.
     */
    fun registerLiveness(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        liveness = metrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    /**
     * Every 5 minutes: a stall is measured in hours, so a tighter tick would only add load, and the
     * alert's own `for:` window is what decides how fast it fires.
     */
    @Scheduled(
        every = "\${openbank.party.pending-kyc.gauge-interval:5m}",
        delayed = "30s",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "party-pending-kyc-stalled-gauge",
    )
    suspend fun refresh() {
        // Deliberately NOT caught: a failure here must reach the scheduler rather than leave the
        // gauge frozen at its last value while looking healthy — the exact shape #5698 is about.
        val aged = parties.countPendingKycOlderThan(clock.instant().minus(stallAfter))
        stalled.set(aged)
        // Only on the completed path: a heartbeat recorded before or inside a failure would assert
        // the very thing it exists to disprove.
        liveness?.recordSuccess()
        if (aged > 0) {
            log.warnf("%d party(ies) have been PENDING_KYC for longer than %s", aged, stallAfter)
        }
    }

    private companion object {
        const val STAGE = "party-pending-kyc"
        const val WORKFLOW_NAME = "party-pending-kyc-stalled-gauge"
        val EXPECTED_INTERVAL: Duration = Duration.ofMinutes(5)
    }
}
