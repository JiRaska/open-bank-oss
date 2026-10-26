// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.scheduler

import com.openbank.billing.application.usecase.ProductShadowService
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Duration

/**
 * Drives the product-IR shadow comparator (ADR-0352 phase 3) every few minutes, off the posting path.
 * Read-only towards money: it compares and counts, it never posts, so it is ON by default — a shadow
 * that is off measures nothing and the switch evidence ADR-0352 §9 requires would never accrue.
 *
 * `suspend`, never `runBlocking`: a plain @Scheduled method runs on a bare executor thread with no
 * Vert.x context and every reactive Panache call from it fails (rules.yaml scheduled_methods).
 * Liveness (ADR-0237) is registered only when enabled, so a deployment that turns the shadow off
 * does not report a permanently stale workflow.
 */
@Startup
@ApplicationScoped
class ProductShadowScheduler(
    private val service: ProductShadowService,
    private val domainMetrics: DomainMetrics,
    // No Kotlin defaults on these (rules.yaml: a defaulted @ConfigProperty constructor parameter makes Arc
    // build the bean through the synthetic constructor and never apply the config); application.yaml
    // supplies every value.
    @ConfigProperty(name = "openbank.billing.product-shadow.enabled")
    private val enabled: Boolean,
    @ConfigProperty(name = "openbank.billing.product-shadow.batch-size")
    private val batchSize: Int,
    @ConfigProperty(name = "openbank.billing.product-shadow.max-batches-per-run")
    private val maxBatches: Int,
) {

    private var liveness: WorkflowLivenessRecorder? = null
    private val log = Logger.getLogger(ProductShadowScheduler::class.java)

    // Registered at boot (`@Startup` makes this bean eager), and only when enabled, so a deployment that
    // turns the shadow off does not report a permanently stale workflow.
    init {
        if (enabled) {
            liveness =
                domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, Duration.ofMinutes(EXPECTED_INTERVAL_MINUTES))
        }
    }

    @Scheduled(
        every = "{openbank.billing.product-shadow.every}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    // `: Unit` is required: Quarkus refuses a suspending @Scheduled method with any other return type
    // ("Suspending scheduled business method must return Unit"), and `= runOnce()` would infer Int.
    suspend fun tick() {
        runOnce()
    }

    /** One run: drains up to [maxBatches] batches. Split from [tick] so tests drive it directly. */
    suspend fun runOnce(): Int {
        if (!enabled) return 0
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
        const val WORKFLOW_NAME = "billing-product-shadow"
        private const val EXPECTED_INTERVAL_MINUTES = 5L
    }
}
