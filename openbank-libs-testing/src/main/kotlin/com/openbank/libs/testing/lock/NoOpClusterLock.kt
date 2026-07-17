// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.lock

import com.openbank.libs.persistence.lock.ClusterLock

/**
 * Always runs [ClusterLock.tryRunExclusively]'s block, unconditionally — for a scheduler's own
 * pure unit tests (mocked repositories, no Quarkus/DB context), which are testing that
 * scheduler's business logic, not [ClusterLock]'s cross-pod exclusion. The real exclusion
 * behaviour is proven separately against a live Postgres (`PostgresClusterLock`'s IT coverage in
 * `openbank-ledger-service`).
 */
class NoOpClusterLock : ClusterLock {
    override suspend fun <T> tryRunExclusively(jobName: String, block: suspend () -> T): T = block()
}
