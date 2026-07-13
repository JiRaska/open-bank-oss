// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.govaudit.application.workflow

import com.openbank.govaudit.application.port.out.GitHubReadPort
import com.openbank.govaudit.domain.model.MergedPullRequest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.jboss.logging.Logger
import java.time.Instant

@ApplicationScoped
open class CollectMergedPrsActivityImpl(private val githubRead: GitHubReadPort) : CollectMergedPrsActivity {

    private val log = Logger.getLogger(CollectMergedPrsActivityImpl::class.java)

    override fun collect(sinceEpochMilli: Long): List<MergedPullRequest> = runOnVertxContext {
        val since = Instant.ofEpochMilli(sinceEpochMilli)
        log.infof("Collecting PRs merged to main since %s", since)
        githubRead.listMergedPrsSince(since)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    protected open fun <T> runOnVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }
}
