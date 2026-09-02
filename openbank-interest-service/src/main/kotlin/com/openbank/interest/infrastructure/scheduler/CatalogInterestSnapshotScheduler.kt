// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.interest.infrastructure.scheduler

import com.openbank.interest.infrastructure.catalog.CatalogInterestProfileSynchronizer
import com.openbank.interest.infrastructure.catalog.CatalogInterestSyncOutcome
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import io.smallrye.config.ConfigMapping
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.jboss.logging.Logger
import java.time.Duration

/** Polls one durable catalog event per run; see [CatalogInterestProfileSynchronizer]. */
@ApplicationScoped
internal class CatalogInterestSnapshotScheduler(
    private val synchronizer: CatalogInterestProfileSynchronizer,
    private val config: CatalogInterestSnapshotConfig,
    private val domainMetrics: DomainMetrics,
) {
    private val log = Logger.getLogger(CatalogInterestSnapshotScheduler::class.java)
    private var liveness: WorkflowLivenessRecorder? = null

    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        if (config.enabled()) liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW, EXPECTED_INTERVAL)
    }

    @Scheduled(
        every = "{interest.catalog-sync.interval}",
        delayed = "{interest.catalog-sync.initial-delay}",
        identity = "interest-catalog-rate-snapshot-sync",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun synchronize() {
        if (!config.enabled()) return
        val outcome = synchronizer.synchronizeOne()
            .onFailure().invoke { e ->
                // The cursor intentionally stays put on transport/auth failures. A subsequent tick retries
                // the same event, rather than allowing a temporary catalog outage to change money inputs.
                log.error("catalog interest snapshot sync failed; cursor was not advanced", e)
            }
            .onFailure().recoverWithItem(CatalogInterestSyncOutcome.FAILED)
            .awaitSuspending()
        if (outcome != CatalogInterestSyncOutcome.FAILED) {
            liveness?.recordSuccess()
            if (outcome ==
                CatalogInterestSyncOutcome.REJECTED
            ) {
                log.warn("catalog interest event rejected; see durable receipt")
            }
        }
    }

    private companion object {
        const val WORKFLOW = "interest-catalog-rate-snapshot-sync"
        val EXPECTED_INTERVAL: Duration = Duration.ofMinutes(1)
    }
}

@ConfigMapping(prefix = "interest.catalog-sync")
interface CatalogInterestSnapshotConfig {
    fun enabled(): Boolean

    // These values are also referenced by @Scheduled. Keeping them in the mapping makes SmallRye
    // validate the whole configuration subtree instead of treating scheduler-only keys as unknown.
    fun interval(): Duration

    fun initialDelay(): Duration
}
