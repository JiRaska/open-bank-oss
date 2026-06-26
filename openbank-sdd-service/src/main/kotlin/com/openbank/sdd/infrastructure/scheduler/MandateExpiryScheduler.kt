// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.sdd.infrastructure.scheduler

import com.openbank.sdd.application.port.out.SddMandateRepository
import com.openbank.sdd.domain.lifecycle.MandateLifecycle
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock
import java.time.LocalDate

/**
 * Idle-expiry sweep (ADR-0036 §B): mandates with no collection for 36 months auto-`EXPIRED`.
 * Disabled by default (`openbank.sdd.expiry.enabled`); the pure date arithmetic lives in
 * [MandateLifecycle.isIdle] and is unit-tested independently of the cron.
 */
@ApplicationScoped
class MandateExpiryScheduler(
    private val mandates: SddMandateRepository,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.sdd.expiry.enabled", defaultValue = "false")
    private val enabled: Boolean,
) {
    @Scheduled(cron = "{openbank.sdd.expiry-cron}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun sweep(): Uni<Void> {
        if (!enabled) return Uni.createFrom().voidItem()
        val today = LocalDate.now(clock)
        return mandates.listLive()
            .onItem().transformToMulti { Multi.createFrom().iterable(it) }
            .onItem().transformToUniAndConcatenate { mandate ->
                val expired = MandateLifecycle.expireIfIdle(mandate, today)
                if (expired.status != mandate.status) mandates.save(expired) else Uni.createFrom().item(mandate)
            }
            .collect().asList()
            .replaceWithVoid()
    }
}
