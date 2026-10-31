// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import java.time.Duration
import java.time.Instant

/**
 * The framework-free half of SENT-row retention (ADR-0327 D8): the bounded batch loop, kept here
 * so it is unit-testable without a database. The scheduler that drives it lives in libs-runtime
 * (`OutboxSentRetentionJob`).
 */
object OutboxRetention {
    /** ADR-0327 D8: SENT rows are replay/debug material only; seven days covers a long weekend plus triage. */
    const val DEFAULT_SENT_DAYS: Long = 7
    const val DEFAULT_BATCH_SIZE: Int = 5_000

    /**
     * Ceiling on batches per target per run, so a first run against a table that has never been
     * purged (years of SENT rows) cannot hold one nightly tick for hours. 200 × 5 000 = 1 M rows a
     * night per outbox; the remainder goes the next night.
     */
    const val DEFAULT_MAX_BATCHES: Int = 200

    /**
     * Purge [target]'s SENT rows older than [olderThan], one `batch`-sized delete at a time, until a
     * delete comes back short or [maxBatches] is reached. One cut-off ([now]) for the whole run, so a
     * long run does not chase rows that became eligible while it was working. Returns the total
     * deleted. Exceptions propagate: the caller decides what one failed target means for the others.
     */
    suspend fun purgeSentUntilShort(
        target: SentOutboxRetention,
        olderThan: Duration,
        batch: Int,
        maxBatches: Int,
        now: Instant,
    ): Long {
        require(!olderThan.isNegative && !olderThan.isZero) { "SENT retention must be positive, was $olderThan" }
        require(batch > 0) { "retention batch must be positive, was $batch" }
        require(maxBatches > 0) { "retention max-batches must be positive, was $maxBatches" }
        var total = 0L
        repeat(maxBatches) {
            val deleted = target.purgeSent(olderThan, batch, now)
            total += deleted
            if (deleted < batch) return total
        }
        return total
    }

    /**
     * `ScaOutboxRepositoryImpl_ClientProxy` -> `sca`. Cut at the first `_` (Arc suffix, #5143), drop
     * the conventional `OutboxRepository[Impl]` / `OutboxPort[Impl]` / `Outbox` tail, kebab-case.
     */
    fun deriveLabel(simpleClassName: String): String = simpleClassName
        .substringBefore('_')
        .replace(Regex("(Outbox)?(Repository|Port|Adapter)?(Impl)?$"), "")
        .removePrefix("Pg")
        .replace(Regex("(?<!^)(?=[A-Z])"), "-")
        .lowercase()
        .ifEmpty { simpleClassName.lowercase() }
}
