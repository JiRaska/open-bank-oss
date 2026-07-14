// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.docstruth.application.workflow

import com.openbank.docstruth.application.port.out.GovernanceRulesPort
import com.openbank.docstruth.application.port.out.RepoScanPort
import com.openbank.docstruth.domain.model.DocsTruthSnapshot
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.runBlocking
import org.jboss.logging.Logger

// Temporal activity methods are plain synchronous functions invoked on the Temporal SDK's own
// activity worker thread pool (registered via WorkerFactory in DocsTruthWorkerRegistrar) — never
// on a Vert.x event-loop thread. The finops-agent VertxContextSupport.subscribeAndAwait { ... }
// pattern this was copied from exists to bridge a genuinely async Mutiny HTTP call back onto a
// Vert.x duplicated context; there is no such context here to bridge onto, and
// Dispatchers.Unconfined does not move the underlying blocking Files.list/Files.walk/readText
// calls in RepoScanAdapter off-thread anyway, so the wrapping added indirection without moving
// any work off the calling thread. A plain runBlocking on the activity thread is simpler and
// exactly as correct.
@ApplicationScoped
open class CollectRepoScanActivityImpl(
    private val repoScan: RepoScanPort,
    private val governanceRules: GovernanceRulesPort,
) : CollectRepoScanActivity {

    private val log = Logger.getLogger(CollectRepoScanActivityImpl::class.java)

    override fun collect(): DocsTruthSnapshot = runBlocking {
        log.info("Scanning docs/adr/*.md Delivery-Status claims and cross-referencing rules.yaml")
        val adrRecords = repoScan.scanAdrRecords()
        val artifactNames = adrRecords.flatMap { adr -> adr.claimedArtifacts.map { it.name } }.toSet()
        val gateNames = adrRecords.flatMap { adr -> adr.claimedEnforcements.map { it.gateName } }.toSet()
        val artifactExistence = repoScan.findArtifacts(artifactNames)
        val gateEnforcementStatus = governanceRules.enforcedStatusFor(gateNames)
        DocsTruthSnapshot(
            adrRecords = adrRecords,
            artifactExistence = artifactExistence,
            gateEnforcementStatus = gateEnforcementStatus,
        )
    }
}
