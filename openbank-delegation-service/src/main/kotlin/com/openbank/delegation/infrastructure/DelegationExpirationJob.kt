// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure

import com.openbank.delegation.application.port.out.DelegationRepository
import com.openbank.delegation.domain.event.DelegationExpired
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
 * Hourly sweep transitioning ACTIVE grants past validTo to EXPIRED, enqueueing a
 * DelegationExpired outbox entry per grant — product-service projections (ADR-0232 D3)
 * must hear the expiry, not just compute it, so their enforcement rows close too.
 * Status flip + outbox enqueue share one transaction (DelegationRepository.markExpired).
 */
@Suppress("TooGenericExceptionCaught") // a sweep must survive one bad grant, and log why
@ApplicationScoped
class DelegationExpirationJob {

    @Inject
    lateinit var delegationRepo: DelegationRepository

    @Inject
    lateinit var clock: Clock

    @Inject
    lateinit var domainMetrics: DomainMetrics

    private var liveness: WorkflowLivenessRecorder? = null

    /** Registers the boot-seeded ADR-0237 heartbeat before the first hourly sweep. */
    fun registerLiveness(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    /**
     * `suspend fun` — the fleet convention (rules.yaml: scheduled_methods), and here it is load
     * bearing. Quarkus invokes a PLAIN @Scheduled method on a bare executor thread with NO Vert.x
     * context, so every reactive Panache call underneath throws HR000068
     * ("No current Vertx context found"). This method used to be plain and to bridge with
     * `.subscribe().with(onFailure = Log.errorf)`, which swallowed exactly that into one ERROR
     * line per hour: the sweep had never expired a grant, and nothing downstream could tell,
     * because a grant past `validTo` still reads as expired to anyone who computes it — only the
     * projections that need the EVENT stay open. Same defect class as #2148/#2187, in the Mutiny
     * shape the runBlocking guard does not match.
     *
     * `awaitSuspending()` inside a suspend scheduled method runs on a context Quarkus does supply.
     */
    @Scheduled(cron = "{openbank.delegation.expiration.cron}", identity = "delegation-expiration-sweep")
    suspend fun sweepExpiredGrants() {
        val threshold = OffsetDateTime.now(clock)
        val count = try {
            buildSweepPipeline(threshold).awaitSuspending()
        } catch (e: Exception) {
            Log.errorf(e, "delegation.expiration.sweep FAILED threshold=%s", threshold)
            return
        }
        liveness?.recordSuccess()
        if (count > 0) {
            Log.infof("delegation.expiration.sweep expired=%d threshold=%s", count, threshold)
        }
    }

    companion object {
        private const val WORKFLOW_NAME = "delegation-expiration-sweep"
        private val EXPECTED_INTERVAL: Duration = Duration.ofHours(1)
    }

    internal fun buildSweepPipeline(threshold: OffsetDateTime): Uni<Int> = delegationRepo.findExpiredActive(threshold)
        .flatMap { expired ->
            if (expired.isEmpty()) return@flatMap Uni.createFrom().item(0)
            Multi.createFrom().iterable(expired)
                .onItem().transformToUniAndConcatenate { grant ->
                    delegationRepo.markExpired(
                        grant.id,
                        grant.lifecycleRevision,
                        threshold,
                        DelegationExpired(
                            aggregateId = grant.id,
                            lifecycleRevision = Math.addExact(grant.lifecycleRevision, 1),
                            grantorPartyId = grant.grantorPartyId,
                            granteePartyId = grant.granteePartyId,
                            resourceType = grant.resourceType,
                            resourceId = grant.resourceId,
                            occurredAt = threshold.toInstant(),
                        ),
                    ).map { transitioned -> if (transitioned) 1 else 0 }
                }
                .collect().asList()
                .map { results -> results.sum() }
        }
}
