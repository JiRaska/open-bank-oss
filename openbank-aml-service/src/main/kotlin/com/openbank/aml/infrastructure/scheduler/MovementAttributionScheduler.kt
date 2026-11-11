// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.scheduler

import com.openbank.aml.application.port.out.AccountPartyPort
import com.openbank.aml.application.port.out.MovementAttribution
import com.openbank.aml.application.port.out.MovementIntakeRepository
import com.openbank.aml.domain.monitoring.CounterScheme
import com.openbank.aml.domain.monitoring.UncountedMovement
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Attributes recorded movements to a party and a rail, and increments the per-party counters
 * (ADR-0371 §2).
 *
 * A booked-movement event names an account, not a party, and no rail. Both are joined here, after
 * the fact, so that intake never depends on another service:
 *  - party: the account authority, remembered locally after the first answer;
 *  - rail: the originating transaction's event, if one exists. A journal posted outside the
 *    transaction flow (a fee, interest) has no such event, so after a grace period the movement is
 *    counted under [NO_ORIGIN_RAIL] rather than waiting forever.
 *
 * A movement whose party cannot be resolved stays uncounted and is retried; it goes to the back of
 * the queue so one unresolvable account cannot starve the rest. The backlog and the age of its
 * oldest row are published, because a sweep that quietly counts nothing looks exactly like a quiet
 * bank.
 *
 * `suspend`, never `runBlocking`: a plain `@Scheduled` method has no Vert.x context.
 */
@Startup
@ApplicationScoped
class MovementAttributionScheduler(
    // Constructor parameters without a Kotlin default: a default would make Arc build the bean
    // through a synthetic constructor and the configured value would never be applied.
    @ConfigProperty(name = "openbank.aml.monitoring.attribution.enabled", defaultValue = "true")
    private val enabled: Boolean,
    @ConfigProperty(name = "openbank.aml.monitoring.attribution.batch-limit", defaultValue = "200")
    private val batchLimit: Int,
    @ConfigProperty(name = "openbank.aml.monitoring.attribution.origin-grace", defaultValue = "PT5M")
    private val originGrace: Duration,
) {

    @Inject
    lateinit var repository: MovementIntakeRepository

    @Inject
    lateinit var accountParty: AccountPartyPort

    @Inject
    lateinit var scheme: CounterScheme

    @Inject
    lateinit var clock: Clock

    @Inject
    lateinit var registryInstance: Instance<MeterRegistry>

    @Inject
    lateinit var domainMetrics: DomainMetrics

    private val log = Logger.getLogger(MovementAttributionScheduler::class.java)
    private val uncounted = AtomicLong(0)
    private val oldestAgeSeconds = AtomicLong(0)
    private var liveness: WorkflowLivenessRecorder? = null

    @PostConstruct
    fun register() {
        if (registryInstance.isResolvable) {
            val registry = registryInstance.get()
            Gauge.builder("openbank.aml.monitoring.movements.uncounted", uncounted) { it.get().toDouble() }
                .tag("service", "aml").strongReference(true).register(registry)
            Gauge.builder("openbank.aml.monitoring.movements.oldest_uncounted_age_seconds", oldestAgeSeconds) {
                it.get().toDouble()
            }.tag("service", "aml").strongReference(true).register(registry)
        }
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    @Scheduled(
        every = "{openbank.aml.monitoring.attribution.interval:1m}",
        delayed = "{openbank.aml.monitoring.attribution.initial-delay:1m}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun tick() {
        if (!enabled) return
        val now = clock.instant()
        var counted = 0
        var deferred = 0
        for (movement in repository.findUncounted(batchLimit)) {
            if (attribute(movement, now)) counted++ else deferred++
        }
        if (counted > 0 || deferred > 0) {
            log.infof("[movement-attribution] counted %d movement(s), deferred %d", counted, deferred)
        }
        val backlog = repository.backlog()
        uncounted.set(backlog.uncounted)
        oldestAgeSeconds.set(backlog.oldestRecordedAt?.let { Duration.between(it, now).seconds.coerceAtLeast(0) } ?: 0)
        liveness?.recordSuccess()
    }

    private suspend fun attribute(movement: UncountedMovement, now: Instant): Boolean {
        val rail = movement.originRail
            ?: NO_ORIGIN_RAIL.takeIf { Duration.between(movement.recordedAt, now) >= originGrace }
        val partyId = movement.knownPartyId ?: accountParty.findPartyByAccountId(movement.accountId)
            ?.also { repository.rememberParty(movement.accountId, it, now) }
        if (rail == null || partyId == null) {
            repository.markAttempted(movement, now)
            return false
        }
        return repository.count(
            MovementAttribution(
                movement = movement,
                partyId = partyId,
                rail = rail,
                schemeRevision = scheme.revision,
                bucketSeconds = scheme.bucketSeconds,
                bucketStart = scheme.bucketStart(movement.bookedAt),
                bandCodes = scheme.bandCodesFor(movement.currency, movement.amount),
                countedAt = now,
            ),
        )
    }

    companion object {
        /** Rail of a booked movement that no originating transaction event ever named. */
        const val NO_ORIGIN_RAIL = "NONE"
        const val WORKFLOW_NAME = "aml-movement-attribution"
        private val EXPECTED_INTERVAL: Duration = Duration.ofMinutes(1)
    }
}
