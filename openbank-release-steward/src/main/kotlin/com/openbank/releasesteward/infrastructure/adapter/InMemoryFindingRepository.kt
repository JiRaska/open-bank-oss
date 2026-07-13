// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.releasesteward.infrastructure.adapter

import com.openbank.releasesteward.application.port.out.FindingRepository
import com.openbank.releasesteward.domain.model.FindingStatus
import com.openbank.releasesteward.domain.model.ReleaseStewardFinding
import jakarta.enterprise.context.ApplicationScoped
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory finding repository.
 *
 * Sufficient for the initial scaffolding phase (ADR-0165), matching the
 * finops-agent/devops-agent/control-liveness-sentinel/governance-auditor bootstrap pattern.
 * Persistence to PostgreSQL is tracked in a follow-up; findings persist only for the lifetime of
 * the pod.
 */
@ApplicationScoped
class InMemoryFindingRepository : FindingRepository {

    private val store = ConcurrentHashMap<String, ReleaseStewardFinding>()

    override suspend fun save(finding: ReleaseStewardFinding): ReleaseStewardFinding {
        store[finding.id] = finding
        return finding
    }

    override suspend fun findActive(): List<ReleaseStewardFinding> =
        store.values.filter { it.status !in setOf(FindingStatus.RESOLVED, FindingStatus.REJECTED) }

    override suspend fun findById(id: String): ReleaseStewardFinding? = store[id]

    override suspend fun update(finding: ReleaseStewardFinding): ReleaseStewardFinding {
        store[finding.id] = finding
        return finding
    }
}
