// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.outbox

import com.openbank.libs.persistence.outbox.AbstractOutboxDispatcher
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxEventPublisher
import com.openbank.libs.persistence.outbox.OutboxRepository
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.faulttolerance.Bulkhead
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout

/**
 * Drains the billing outbox to the ledger (ADR-0143 step 2 / ADR-0050 / ADR-0049 D3).
 *
 * Extends [AbstractOutboxDispatcher] for the shared dispatch loop; each entry is "published" by
 * [LedgerOutboxEventPublisher], i.e. posted to ledger-service, not emitted to Kafka — billing has
 * no downstream event consumers for the charge itself (ADR-0143 explicitly scopes this as
 * ledger-posting, not event publication). The `dispatchEnabled` guard lets GitOps enable draining
 * per-environment without a redeploy (same convention as interest/standing-order/sepa-payment).
 *
 * **N4 — single writer.** `concurrentExecution = SKIP` prevents in-JVM overlap; the billing
 * Deployment should be pinned to `replicas: 1` (documented in the threat model / runbook).
 * Resilience policies apply on [publishWithResilience] (cross-bean CDI call through the proxy,
 * ADR-0013) — this is IN ADDITION to [com.openbank.billing.infrastructure.adapter.LedgerPostingAdapter]'s
 * own inner resilience on the raw HTTP call; the outer retry here covers a failure anywhere in
 * [LedgerOutboxEventPublisher.publish] (deserialization, the ledger call, or the `markPosted` write).
 */
@ApplicationScoped
class BillingOutboxDispatcher(
    private val repo: OutboxRepository,
    private val publisher: LedgerOutboxEventPublisher,
    @ConfigProperty(name = "openbank.outbox.dispatch-enabled", defaultValue = "false")
    private val dispatchEnabled: Boolean,
) : AbstractOutboxDispatcher() {

    override val outboxRepository: OutboxRepository get() = repo
    override val outboxEventPublisher: OutboxEventPublisher get() = publisher

    @Scheduled(
        every = "\${openbank.outbox.poll-interval:5s}",
        delayed = "\${openbank.outbox.initial-delay:5s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "billing-outbox-dispatcher",
    )
    @Bulkhead(BULKHEAD_CONCURRENT_CALLS)
    @CircuitBreaker(requestVolumeThreshold = CB_VOLUME_THRESHOLD, failureRatio = CB_FAILURE_RATIO, delay = CB_DELAY_MS)
    @Retry(maxRetries = MAX_RETRIES, delay = RETRY_DELAY_MS, jitter = RETRY_JITTER_MS)
    @Timeout(DISPATCH_TIMEOUT_MS)
    suspend fun dispatch() {
        if (dispatchEnabled) dispatchScheduledBatch()
    }

    /** Exposed for ITs: drives one dispatch cycle without the `@Scheduled` / resilience annotations. */
    public override suspend fun dispatchScheduledBatch() = super.dispatchScheduledBatch()

    @Bulkhead(BULKHEAD_CONCURRENT_CALLS)
    @CircuitBreaker(requestVolumeThreshold = CB_VOLUME_THRESHOLD, failureRatio = CB_FAILURE_RATIO, delay = CB_DELAY_MS)
    @Retry(maxRetries = MAX_RETRIES, delay = RETRY_DELAY_MS, jitter = RETRY_JITTER_MS)
    @Timeout(PUBLISH_TIMEOUT_MS)
    override suspend fun publishWithResilience(entry: OutboxEntry): Unit = publisher.publish(entry)

    private companion object {
        // Resilience tuning (ADR-0050 / mirrors InterestOutboxDispatcher / LendingOutboxDispatcher).
        const val BULKHEAD_CONCURRENT_CALLS = 1
        const val CB_VOLUME_THRESHOLD = 10
        const val CB_FAILURE_RATIO = 0.5
        const val CB_DELAY_MS = 5000L
        const val MAX_RETRIES = 2
        const val RETRY_DELAY_MS = 200L
        const val RETRY_JITTER_MS = 100L
        const val DISPATCH_TIMEOUT_MS = 30000L
        const val PUBLISH_TIMEOUT_MS = 5000L
    }
}
