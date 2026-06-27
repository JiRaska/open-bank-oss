// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.reconcile

import com.openbank.analytics.application.port.out.ReconciliationSource
import com.openbank.analytics.application.port.out.WarehouseStateReader
import com.openbank.libs.analytics.AggregateKey
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Default

/**
 * Default reconciliation port bindings: both sides return empty, so a reconciliation pass is a
 * clean no-op (drift = 0) until the OLTP readers and the ClickHouse reader are wired. Keeps the
 * service offline-buildable while the real [com.openbank.libs.analytics.Reconciliation.diff] logic
 * (unit-tested in openbank-libs) is exercised end-to-end through [ReconciliationJob].
 */
@ApplicationScoped
@Default
class NoOpReconciliationSource : ReconciliationSource {
    override suspend fun currentVersions(): Map<AggregateKey, Long> = emptyMap()
}

@ApplicationScoped
@Default
class NoOpWarehouseStateReader : WarehouseStateReader {
    override suspend fun currentVersions(): Map<AggregateKey, Long> = emptyMap()
}
