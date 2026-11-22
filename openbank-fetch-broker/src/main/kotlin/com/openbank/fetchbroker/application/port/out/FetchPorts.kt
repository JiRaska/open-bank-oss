// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.fetchbroker.application.port.out

import com.openbank.fetchbroker.domain.FetchEnvelope
import com.openbank.fetchbroker.domain.FetchMode
import com.openbank.fetchbroker.domain.FetchOutcome
import com.openbank.fetchbroker.domain.HostGrant
import com.openbank.fetchbroker.domain.RenderResult
import java.net.URI

/** Per-charter `data_scope.public_web.hosts` (ADR-0387 D1). Empty map ⇒ every host is denied. */
fun interface CharterHostsPort {
    fun grantsFor(agentId: String): Map<String, HostGrant>
}

class RenderRequest(val uri: URI, val mode: FetchMode, val grants: Map<String, HostGrant>)

/** Plain HTTP or the JS renderer, behind one port so the provider can be switched off (ADR-0387 D3). */
interface RendererPort {
    val name: String

    fun render(request: RenderRequest): RenderResult
}

interface FetchMetricsPort {
    fun recorded(outcome: FetchOutcome, host: String)

    /** Workflow liveness (ADR-0237) — called on FETCHED only. */
    fun fetchSucceeded()
}

interface FetchAuditPort {
    suspend fun fetched(agentId: String, envelope: FetchEnvelope)
}
