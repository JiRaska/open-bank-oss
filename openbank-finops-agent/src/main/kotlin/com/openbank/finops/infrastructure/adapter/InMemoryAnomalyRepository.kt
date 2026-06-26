// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.finops.infrastructure.adapter

import com.openbank.finops.application.port.out.AnomalyRepository
import com.openbank.finops.domain.model.AnomalyStatus
import com.openbank.finops.domain.model.CostAnomaly
import jakarta.enterprise.context.ApplicationScoped
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory anomaly repository.
 *
 * Sufficient for the initial scaffolding phase (ADR-0112 P3). Persistence to PostgreSQL
 * is tracked in the follow-up issue; anomalies persist only for the lifetime of the pod.
 */
@ApplicationScoped
class InMemoryAnomalyRepository : AnomalyRepository {

    private val store = ConcurrentHashMap<String, CostAnomaly>()

    override suspend fun save(anomaly: CostAnomaly): CostAnomaly {
        store[anomaly.id] = anomaly
        return anomaly
    }

    override suspend fun findActive(): List<CostAnomaly> =
        store.values.filter { it.status !in setOf(AnomalyStatus.RESOLVED, AnomalyStatus.REJECTED) }

    override suspend fun findById(id: String): CostAnomaly? = store[id]

    override suspend fun update(anomaly: CostAnomaly): CostAnomaly {
        store[anomaly.id] = anomaly
        return anomaly
    }
}
