// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure

import com.openbank.delegation.application.port.out.DelegationRepository
import com.openbank.delegation.domain.event.DelegationExpired
import io.quarkus.logging.Log
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
import java.time.OffsetDateTime

/**
 * Hourly sweep transitioning ACTIVE grants past validTo to EXPIRED, enqueueing a
 * DelegationExpired outbox entry per grant — product-service projections (ADR-0232 D3)
 * must hear the expiry, not just compute it, so their enforcement rows close too.
 * Status flip + outbox enqueue share one transaction (DelegationRepository.markExpired).
 */
@ApplicationScoped
class DelegationExpirationJob {

    @Inject
    lateinit var delegationRepo: DelegationRepository

    @Inject
    lateinit var clock: Clock

    @Scheduled(cron = "0 7 * * * ?", identity = "delegation-expiration-sweep")
    fun sweepExpiredGrants() {
        val threshold = OffsetDateTime.now(clock)
        buildSweepPipeline(threshold)
            .subscribe().with(
                { count ->
                    if (count > 0) {
                        Log.infof("delegation.expiration.sweep expired=%d threshold=%s", count, threshold)
                    }
                },
                { err -> Log.errorf(err, "delegation.expiration.sweep FAILED threshold=%s", threshold) },
            )
    }

    internal fun buildSweepPipeline(threshold: OffsetDateTime): Uni<Int> = delegationRepo.findExpiredActive(threshold)
        .flatMap { expired ->
            if (expired.isEmpty()) return@flatMap Uni.createFrom().item(0)
            Multi.createFrom().iterable(expired)
                .onItem().transformToUniAndConcatenate { grant ->
                    delegationRepo.markExpired(
                        grant.id,
                        threshold,
                        DelegationExpired(
                            aggregateId = grant.id,
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
