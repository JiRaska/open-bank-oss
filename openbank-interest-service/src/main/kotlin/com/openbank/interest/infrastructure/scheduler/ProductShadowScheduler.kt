// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.scheduler

import com.openbank.interest.application.usecase.ProductShadowService
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Duration

/**
 * Drives the interest product-IR shadow (ADR-0352 phase 3) every few minutes, off the posting path:
 * first it enqueues committed accruals and capitalisations that have no queue row yet, then it drains
 * the queue. Read-only towards money — it compares and counts, it never posts — so it is ON by default:
 * a shadow that is off accrues none of the switch evidence ADR-0352 §9 requires.
 *
 * `suspend`, never `runBlocking`: a plain @Scheduled method has no Vert.x context and every reactive
 * Panache call from it fails (rules.yaml scheduled_methods). Liveness (ADR-0237) is registered at boot
 * and only when enabled, so a deployment that turns the shadow off does not report a stale workflow.
 */
@Startup
@ApplicationScoped
class ProductShadowScheduler(
    private val service: ProductShadowService,
    private val domainMetrics: DomainMetrics,
    // No Kotlin defaults (a defaulted @ConfigProperty constructor parameter makes Arc use the synthetic
    // constructor and never apply the config); application.yaml supplies every value.
    @ConfigProperty(name = "openbank.interest.product-shadow.enabled")
    private val enabled: Boolean,
    @ConfigProperty(name = "openbank.interest.product-shadow.batch-size")
    private val batchSize: Int,
    @ConfigProperty(name = "openbank.interest.product-shadow.max-batches-per-run")
    private val maxBatches: Int,
    @ConfigProperty(name = "openbank.interest.product-shadow.lookback-days")
    private val lookbackDays: Long,
) {

    private var liveness: WorkflowLivenessRecorder? = null
    private val log = Logger.getLogger(ProductShadowScheduler::class.java)

    init {
        if (enabled) {
            liveness =
                domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, Duration.ofMinutes(EXPECTED_INTERVAL_MINUTES))
        }
    }

    @Scheduled(
        every = "{openbank.interest.product-shadow.every}",
        identity = "interest-product-shadow",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun tick() {
        runOnce()
    }

    /** One run: enqueue, then drain up to [maxBatches] batches. Split from [tick] so tests drive it directly. */
    suspend fun runOnce(): Int {
        if (!enabled) return 0
        service.intake(lookbackDays, batchSize * maxBatches)
        var total = 0
        repeat(maxBatches) {
            val n = service.runOnce(batchSize)
            total += n
            if (n < batchSize) {
                liveness?.recordSuccess()
                return total
            }
        }
        log.infof("product shadow: backlog larger than %d inputs per run; continuing next tick", batchSize * maxBatches)
        liveness?.recordSuccess()
        return total
    }

    companion object {
        const val WORKFLOW_NAME = "interest-product-shadow"
        private const val EXPECTED_INTERVAL_MINUTES = 5L
    }
}
