// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sdd.infrastructure.scheduler

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.sdd.application.port.out.SddMandateRepository
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

/**
 * Idle-expiry sweep (ADR-0036 §B): mandates with no collection for 36 months auto-`EXPIRED`.
 *
 * The kill switch `openbank.sdd.expiry.enabled` defaults to `false` here and in `application.yaml`;
 * each environment turns it on through `OPENBANK_SDD_EXPIRY_ENABLED` in its GitOps manifest, so it
 * can be switched off without a revert. Each mandate is expired in its own transaction via
 * [MandateExpiryWriter], which also writes `sdd.mandate.expired.v1` to the outbox. A failing mandate
 * is counted and logged and the sweep continues; the pure date arithmetic lives in
 * `MandateLifecycle.isIdle`.
 */
@ApplicationScoped
class MandateExpiryScheduler(
    private val mandates: SddMandateRepository,
    private val writer: MandateExpiryWriter,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.sdd.expiry.enabled", defaultValue = "false")
    private val enabled: Boolean,
    private val domainMetrics: DomainMetrics,
) {
    @Inject
    lateinit var registryInstance: Instance<MeterRegistry>

    private var liveness: WorkflowLivenessRecorder? = null
    private var expiredCounter: Counter? = null
    private var failedCounter: Counter? = null

    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") ev: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, Duration.ofDays(1))
        if (::registryInstance.isInitialized && registryInstance.isResolvable) {
            val registry = registryInstance.get()
            expiredCounter = Counter.builder(EXPIRED_METRIC)
                .description("SDD mandates moved to EXPIRED by the idle-expiry sweep")
                .register(registry)
            failedCounter = Counter.builder(FAILED_METRIC)
                .description("SDD mandates the idle-expiry sweep failed to expire (retried next run)")
                .register(registry)
        }
    }

    @Scheduled(cron = "{openbank.sdd.expiry-cron}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun sweep(): Uni<Void> {
        if (!enabled) return Uni.createFrom().voidItem()
        val today = LocalDate.now(clock)
        return mandates.listLive()
            .onItem().transformToMulti { Multi.createFrom().iterable(it) }
            .onItem().transformToUniAndConcatenate { mandate ->
                writer.expireIfIdle(mandate, today)
                    .map { expired -> if (expired) Outcome.EXPIRED else Outcome.UNCHANGED }
                    .onFailure().recoverWithItem { e ->
                        LOG.errorf(e, "SDD idle-expiry failed for mandate %s; continuing sweep", mandate.id)
                        Outcome.FAILED
                    }
            }
            .collect().asList()
            .onItem().invoke { outcomes ->
                val expired = outcomes.count { it == Outcome.EXPIRED }
                val failed = outcomes.count { it == Outcome.FAILED }
                expiredCounter?.increment(expired.toDouble())
                failedCounter?.increment(failed.toDouble())
                LOG.infof("SDD idle-expiry sweep: scanned=%d expired=%d failed=%d", outcomes.size, expired, failed)
                // A partial sweep is still a run of the workflow; failures are visible on the counter
                // and are retried tomorrow (the row is still ACTIVE/SUSPENDED).
                liveness?.recordSuccess()
            }
            .replaceWithVoid()
    }

    internal enum class Outcome { EXPIRED, UNCHANGED, FAILED }

    private companion object {
        val LOG: Logger = Logger.getLogger(MandateExpiryScheduler::class.java)
        const val WORKFLOW_NAME = "sdd-mandate-expiry"
        const val EXPIRED_METRIC = "openbank.sdd.mandate.expiry.expired"
        const val FAILED_METRIC = "openbank.sdd.mandate.expiry.failed"
    }
}
