// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.libs.persistence.lock

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure-function coverage for [ClusterLockKey] (#1201 proposed fix 2). The real cross-pod
 * mutual-exclusion behaviour ([PostgresClusterLock] against a live Postgres) is proven in
 * `openbank-ledger-service`'s IT suite — this class has no Quarkus/DB context, same split as
 * every other pure-logic-vs-real-wiring pair in this codebase.
 */
class ClusterLockKeyTest {

    @Test
    fun `same job name always maps to the same key`() {
        assertThat(ClusterLockKey.of("ledger.tieout")).isEqualTo(ClusterLockKey.of("ledger.tieout"))
    }

    @Test
    fun `different job names map to different keys`() {
        val keys = listOf(
            "ledger.tieout",
            "ledger.tieout.freshness",
            "ledger.fx-revaluation",
            "ledger.partition-maintenance",
        ).map { ClusterLockKey.of(it) }

        assertThat(keys.toSet()).describedAs("no collision among this service's own job names").hasSize(keys.size)
    }

    @Test
    fun `key is a stable, non-negative CRC32 value (pinned so a future change is deliberate)`() {
        // A stable key is load-bearing: it is the identity a running pod and the pod it is
        // racing during a canary window both derive independently from the same job name string,
        // with no shared state — if this function's output ever changed, every in-flight lock
        // key would silently change too, defeating the exclusion mid-rollout.
        assertThat(ClusterLockKey.of("ledger.tieout")).isEqualTo(3_510_320_410L)
        assertThat(ClusterLockKey.of("ledger.tieout")).isGreaterThanOrEqualTo(0L)
    }
}
