// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.liveness.infrastructure.adapter

import com.openbank.liveness.application.port.out.FindingRepository
import com.openbank.liveness.domain.model.FindingStatus
import com.openbank.liveness.domain.model.LivenessFinding
import jakarta.enterprise.context.ApplicationScoped
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory finding repository.
 *
 * Sufficient for the initial scaffolding phase (ADR-0163), matching the finops-agent/devops-agent
 * bootstrap pattern. Persistence to PostgreSQL is tracked in a follow-up; findings persist only
 * for the lifetime of the pod.
 */
@ApplicationScoped
class InMemoryFindingRepository : FindingRepository {

    private val store = ConcurrentHashMap<String, LivenessFinding>()

    override suspend fun save(finding: LivenessFinding): LivenessFinding {
        store[finding.id] = finding
        return finding
    }

    override suspend fun findActive(): List<LivenessFinding> =
        store.values.filter { it.status !in setOf(FindingStatus.RESOLVED, FindingStatus.REJECTED) }

    override suspend fun findById(id: String): LivenessFinding? = store[id]

    override suspend fun update(finding: LivenessFinding): LivenessFinding {
        store[finding.id] = finding
        return finding
    }
}
