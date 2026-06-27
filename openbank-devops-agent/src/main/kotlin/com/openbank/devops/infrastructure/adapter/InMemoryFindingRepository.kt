// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.devops.infrastructure.adapter

import com.openbank.devops.application.port.out.FindingRepository
import com.openbank.devops.domain.model.DevOpsFinding
import com.openbank.devops.domain.model.FindingStatus
import jakarta.enterprise.context.ApplicationScoped
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory finding repository.
 *
 * Sufficient for the initial vertical (ADR-0119). Persistence to PostgreSQL is the documented
 * follow-up; findings persist only for the lifetime of the pod (the Temporal workflow history is
 * the durable record of each run).
 */
@ApplicationScoped
class InMemoryFindingRepository : FindingRepository {

    private val store = ConcurrentHashMap<String, DevOpsFinding>()

    override suspend fun save(finding: DevOpsFinding): DevOpsFinding {
        store[finding.id] = finding
        return finding
    }

    override suspend fun findActive(): List<DevOpsFinding> =
        store.values.filter { it.status !in setOf(FindingStatus.RESOLVED, FindingStatus.REJECTED) }

    override suspend fun findById(id: String): DevOpsFinding? = store[id]

    override suspend fun update(finding: DevOpsFinding): DevOpsFinding {
        store[finding.id] = finding
        return finding
    }
}
