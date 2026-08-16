// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.infrastructure

import com.openbank.consent.application.port.out.ConsentRepository
import com.openbank.consent.domain.event.ConsentExpired
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.quarkus.logging.Log
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime

/**
 * Scheduled sweep that transitions ACTIVE consents past their validTo date to EXPIRED
 * and enqueues a ConsentExpired outbox entry per consent (ADR-0126 §D4).
 *
 * Without this job, expired consents remain ACTIVE in the DB. Consent.isActive() returns
 * false for in-process validation, but downstream consumers never receive the ConsentExpired
 * event and cannot cease data processing (GDPR Art. 17 / PSD2 RTS Art. 10).
 *
 * The status flip and the outbox enqueue share ONE transaction ([ConsentRepository.markExpired]),
 * so the sweep cannot mark a consent EXPIRED without durably enqueueing its event.
 *
 * Runs hourly at minute 5 to avoid the top-of-hour spike.
 *
 * The method MUST stay a `suspend fun`. Quarkus invokes a plain `@Scheduled` method on a bare
 * `executor-thread` that carries no Vert.x context, so a reactive Panache call started from it
 * fails with `HR000068` on every single firing (#2913 — the sweep had never once succeeded).
 * Subscribing to the `Uni` instead of awaiting it does not help: the subscription still starts
 * on the scheduler's thread. A `suspend fun` is the fleet convention
 * (`rules.yaml: scheduled_methods`) and the only shape Quarkus runs on a Vert.x context.
 */
@ApplicationScoped
class ConsentExpirationJob {

    @Inject
    lateinit var consentRepo: ConsentRepository

    @Inject
    lateinit var clock: Clock

    @Inject
    lateinit var domainMetrics: DomainMetrics

    private var liveness: WorkflowLivenessRecorder? = null

    /** Registers the boot-seeded ADR-0237 heartbeat before the first hourly sweep. */
    fun registerLiveness(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    // TooGenericExceptionCaught: one bad tick must not kill the cron — ANY fault is logged and the
    // next hour retries, since every consent still past validTo is picked up again by definition.
    @Suppress("TooGenericExceptionCaught")
    @Scheduled(
        cron = "{openbank.consent.expiration-cron:0 5 * * * ?}",
        identity = "consent-expiration-sweep",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun sweepExpiredConsents() {
        val threshold = OffsetDateTime.now(clock)
        try {
            val count = buildSweepPipeline(threshold).awaitSuspending()
            liveness?.recordSuccess()
            if (count > 0) {
                Log.infof("consent.expiration.sweep expired=%d threshold=%s", count, threshold)
            }
        } catch (err: Exception) {
            Log.errorf(err, "consent.expiration.sweep FAILED threshold=%s", threshold)
        }
    }

    companion object {
        private const val WORKFLOW_NAME = "consent-expiration-sweep"
        private val EXPECTED_INTERVAL: Duration = Duration.ofHours(1)
    }

    internal fun buildSweepPipeline(threshold: OffsetDateTime): Uni<Int> = consentRepo.findExpiredActive(threshold)
        .flatMap { expired ->
            if (expired.isEmpty()) return@flatMap Uni.createFrom().item(0)
            Multi.createFrom().iterable(expired)
                .onItem().transformToUniAndConcatenate { consent ->
                    consentRepo.markExpired(
                        consent.id,
                        threshold,
                        ConsentExpired(
                            aggregateId = consent.id,
                            partyId = consent.partyId,
                            granteeId = consent.granteeId,
                            occurredAt = threshold.toInstant(),
                        ),
                    ).map { transitioned -> if (transitioned) 1 else 0 }
                }
                .collect().asList()
                .map { results -> results.sum() }
        }
}
