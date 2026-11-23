// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.fetchbroker.infrastructure.render

import com.openbank.libs.security.EgressPolicy
import com.openbank.libs.security.EgressRequest
import com.openbank.libs.security.EgressResponse
import com.openbank.libs.security.SafeHttpClient

/**
 * One request through ADR-0320's [SafeHttpClient], with an egress policy built from exactly the
 * hosts this call may reach. A seam so tests can stand in for the network.
 */
fun interface HttpTransport {
    fun send(request: EgressRequest, allowedHosts: Set<String>): EgressResponse

    companion object {
        fun safe(maxResponseBytes: Int): HttpTransport = HttpTransport { request, hosts ->
            // https only, default port, public addresses only — no ;http / ;private flags.
            SafeHttpClient(EgressPolicy.fromConfig(hosts.toList()), maxResponseBytes = maxResponseBytes).send(request)
        }
    }
}
