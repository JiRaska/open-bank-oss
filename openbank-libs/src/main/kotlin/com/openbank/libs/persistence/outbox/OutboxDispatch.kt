// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.persistence.outbox

import org.jboss.logging.Logger

/**
 * Shared outbox dispatch loop. Service-level dispatchers should:
 *   1. annotate their own `@Scheduled(every = "5s", ...)` method
 *   2. delegate to [dispatchOnce], injecting their service's [OutboxRepository] and a
 *      `publishWithResilience` lambda that carries the service-specific @CircuitBreaker /
 *      @Retry / @Bulkhead annotations.
 *
 * Keeping the @Scheduled and @CircuitBreaker annotations service-side preserves CDI proxying
 * (interceptors only fire on direct CDI injection, not on a libs-side base class).
 */
object OutboxDispatch {
    private val log: Logger = Logger.getLogger(OutboxDispatch::class.java)

    const val DEFAULT_BATCH_SIZE = 25

    suspend fun dispatchOnce(
        repository: OutboxRepository,
        batchSize: Int = DEFAULT_BATCH_SIZE,
        publish: suspend (entry: OutboxEntry) -> Unit,
    ) {
        runCatching { repository.listProcessable(batchSize) }
            .onFailure { ex -> log.warnf(ex, "outbox.listProcessable failed") }
            .getOrNull()
            ?.forEach { entry ->
                try {
                    publish(entry)
                    repository.markSent(entry.eventId)
                } catch (ex: Exception) {
                    repository.markFailed(entry.eventId, ex.message ?: ex.javaClass.simpleName)
                }
            }
    }
}
