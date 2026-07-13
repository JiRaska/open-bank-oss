// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.releasesteward.application.workflow

import com.openbank.releasesteward.application.port.out.GitHubOpenPrReadPort
import com.openbank.releasesteward.application.port.out.RepoStateReadPort
import com.openbank.releasesteward.domain.model.ReleaseStewardSnapshot
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.jboss.logging.Logger

@ApplicationScoped
open class CollectRepoStateActivityImpl(
    private val repoState: RepoStateReadPort,
    private val githubOpenPr: GitHubOpenPrReadPort,
) : CollectRepoStateActivity {

    private val log = Logger.getLogger(CollectRepoStateActivityImpl::class.java)

    override fun collect(): ReleaseStewardSnapshot = runOnVertxContext {
        log.info("Collecting release-please/version-axis repo state and open-PR openapi diffs")
        val state = repoState.snapshot()
        val openApiPrChanges = githubOpenPr.listOpenPrsTouchingOpenApi()
        val mainVersions = openApiPrChanges
            .map { it.service }
            .distinct()
            .associateWith { service -> githubOpenPr.mainOpenApiVersion(service) }
            .filterValues { it != null }
            .mapValues { (_, v) -> v!! }
        ReleaseStewardSnapshot(
            repoState = state,
            openApiPrChanges = openApiPrChanges,
            mainOpenApiVersions = mainVersions,
        )
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    protected open fun <T> runOnVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }
}
