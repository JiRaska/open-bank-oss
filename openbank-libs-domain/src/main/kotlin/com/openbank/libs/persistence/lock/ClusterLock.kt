// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.lock

import java.util.zip.CRC32

/**
 * Cross-pod mutual exclusion for a `@Scheduled` bean (#1201 proposed fix 2).
 *
 * `replicas: 1` is steady-state only — an Argo Rollouts canary window runs the old and new pod
 * simultaneously for the whole rollout duration, and both fire every `@Scheduled` bean on their
 * own tick regardless of traffic-weight split. For a job with its own idempotency (the outbox
 * dispatcher) that's handled by a row claim instead (`OutboxRepository.claimProcessable`,
 * #1201 proposed fix 1). This port is for the other kind: a job with **no per-row claim to
 * make** — a whole-tick job like a daily control check, a revaluation run, or partition DDL —
 * where the correct behaviour is simply "only one pod runs this tick at all."
 *
 * An injectable port (not a bare static object) so a scheduler's own pure unit tests can
 * substitute a trivial always-runs fake instead of needing a live Postgres — the real exclusion
 * behaviour is proven separately against a real database (`PostgresClusterLock`'s IT coverage).
 */
interface ClusterLock {
    /**
     * Runs [block] only if this job's lock is free right now; returns its result, or `null` if
     * another instance already holds the lock for [jobName] (routine during a canary window —
     * the losing pod simply skips this tick, same as `concurrentExecution = SKIP` already does
     * for a same-JVM overlap).
     *
     * Not a queue: a losing pod does not wait for the lock and does not retry within this call —
     * the job's own next scheduled tick is the retry.
     */
    suspend fun <T> tryRunExclusively(jobName: String, block: suspend () -> T): T?
}

/**
 * Stable, deterministic mapping from a job name to the `bigint` key a Postgres advisory lock
 * takes. CRC32 rather than `String.hashCode()`: both are stable across JVM versions (the latter
 * is specified in the `String.hashCode` Javadoc), but CRC32 is the conventional choice for a
 * lock/partition key specifically, and its always-non-negative 32-bit range is plenty for the
 * handful of once-per-cluster jobs any one service has — collision risk is negligible at that
 * count. Advisory lock keys are scoped to the current Postgres **database**, not the whole
 * server instance, so no cross-service namespacing is needed as long as each service owns its
 * own database (true for every service in this fleet).
 *
 * A pure function, kept separate from the [ClusterLock] port itself, so it is testable without
 * any DB/CDI context.
 */
object ClusterLockKey {
    fun of(jobName: String): Long {
        val crc = CRC32()
        crc.update(jobName.toByteArray(Charsets.UTF_8))
        return crc.value
    }
}
