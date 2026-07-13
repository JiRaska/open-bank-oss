// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.docstruth.application.workflow

import com.openbank.docstruth.application.port.out.GovernanceRulesPort
import com.openbank.docstruth.application.port.out.RepoScanPort
import com.openbank.docstruth.domain.model.DocsTruthSnapshot
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.jboss.logging.Logger

@ApplicationScoped
open class CollectRepoScanActivityImpl(
    private val repoScan: RepoScanPort,
    private val governanceRules: GovernanceRulesPort,
) : CollectRepoScanActivity {

    private val log = Logger.getLogger(CollectRepoScanActivityImpl::class.java)

    override fun collect(): DocsTruthSnapshot = runOnVertxContext {
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

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    protected open fun <T> runOnVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }
}
