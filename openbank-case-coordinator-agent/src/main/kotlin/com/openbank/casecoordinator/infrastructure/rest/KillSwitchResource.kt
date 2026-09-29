// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

package com.openbank.casecoordinator.infrastructure.rest

import com.openbank.casecoordinator.application.port.out.CaseKillSwitchStatePort
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

@Path("/api/v1/case-coordinator/kill-switch")
@Produces(MediaType.APPLICATION_JSON)
class KillSwitchResource(private val killSwitchState: CaseKillSwitchStatePort) {

    data class KillSwitchScopeResponse(val scope: String, val reason: String, val setBy: String)

    data class KillSwitchStatusResponse(val active: Boolean, val scopes: List<KillSwitchScopeResponse>)

    @GET
    @RolesAllowed("ROLE_ADMIN", "ROLE_VIEWER", "ROLE_OPERATOR")
    fun status(): KillSwitchStatusResponse {
        val scopes = killSwitchState.activeScopes()
        return KillSwitchStatusResponse(
            active = scopes.isNotEmpty(),
            scopes = scopes.map { KillSwitchScopeResponse(it.scope, it.reason, it.setBy) },
        )
    }
}
