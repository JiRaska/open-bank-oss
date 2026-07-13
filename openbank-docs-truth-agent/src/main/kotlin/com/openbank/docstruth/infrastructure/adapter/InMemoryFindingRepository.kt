// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.docstruth.infrastructure.adapter

import com.openbank.docstruth.application.port.out.FindingRepository
import com.openbank.docstruth.domain.model.DocsTruthFinding
import com.openbank.docstruth.domain.model.FindingStatus
import jakarta.enterprise.context.ApplicationScoped
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory finding repository.
 *
 * Sufficient for the initial scaffolding phase (ADR-0166), matching the finops-agent/devops-agent/
 * control-liveness-sentinel/governance-auditor/release-steward bootstrap pattern. Persistence to
 * PostgreSQL is tracked in a follow-up; findings persist only for the lifetime of the pod.
 */
@ApplicationScoped
class InMemoryFindingRepository : FindingRepository {

    private val store = ConcurrentHashMap<String, DocsTruthFinding>()

    override suspend fun save(finding: DocsTruthFinding): DocsTruthFinding {
        store[finding.id] = finding
        return finding
    }

    override suspend fun findActive(): List<DocsTruthFinding> =
        store.values.filter { it.status !in setOf(FindingStatus.RESOLVED, FindingStatus.REJECTED) }

    override suspend fun findById(id: String): DocsTruthFinding? = store[id]

    override suspend fun update(finding: DocsTruthFinding): DocsTruthFinding {
        store[finding.id] = finding
        return finding
    }
}
