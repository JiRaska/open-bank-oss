// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.authzaudit.application.workflow

import com.openbank.authzaudit.application.port.out.PolicyScanPort
import com.openbank.authzaudit.domain.model.AuthzPolicySnapshot
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.jboss.logging.Logger

@ApplicationScoped
open class CollectPolicyScanActivityImpl(private val policyScan: PolicyScanPort) : CollectPolicyScanActivity {

    private val log = Logger.getLogger(CollectPolicyScanActivityImpl::class.java)

    override fun collect(): AuthzPolicySnapshot = runOnVertxContext {
        log.info("Scanning OPA/Rego policy sources and agents.yaml for static authz-policy drift")
        policyScan.scan()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    protected open fun <T> runOnVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }
}
