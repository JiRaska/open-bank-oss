// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.infrastructure

import com.openbank.consent.application.port.out.ConsentRepository
import com.openbank.consent.domain.event.ConsentExpired
import io.quarkus.logging.Log
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
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
 * Runs hourly at minute 5 to avoid the top-of-hour spike. The Uni pipeline is fully reactive;
 * subscribe() hands off to the Vert.x I/O pool, never blocking the scheduler thread.
 */
@ApplicationScoped
class ConsentExpirationJob {

    @Inject
    lateinit var consentRepo: ConsentRepository

    @Inject
    lateinit var clock: Clock

    @Scheduled(cron = "0 5 * * * ?", identity = "consent-expiration-sweep")
    fun sweepExpiredConsents() {
        val threshold = OffsetDateTime.now(clock)
        buildSweepPipeline(threshold)
            .subscribe().with(
                { count ->
                    if (count >
                        0
                    ) {
                        Log.infof("consent.expiration.sweep expired=%d threshold=%s", count, threshold)
                    }
                },
                { err -> Log.errorf(err, "consent.expiration.sweep FAILED threshold=%s", threshold) },
            )
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
