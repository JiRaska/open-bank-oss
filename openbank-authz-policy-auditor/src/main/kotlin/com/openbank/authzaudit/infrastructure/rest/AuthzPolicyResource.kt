// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.authzaudit.infrastructure.rest

import com.openbank.authzaudit.application.port.incoming.GetFindingsUseCase
import com.openbank.authzaudit.application.port.incoming.RunAuthzPolicyCheckUseCase
import com.openbank.authzaudit.domain.model.AuthzPolicyFinding
import com.openbank.authzaudit.domain.model.AuthzPolicyReport
import com.openbank.authzaudit.domain.model.RunTrigger
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import kotlinx.coroutines.runBlocking

@Path("/api/v1/authz-policy-auditor")
@Produces(MediaType.APPLICATION_JSON)
class AuthzPolicyResource(
    private val runCheck: RunAuthzPolicyCheckUseCase,
    private val getFindings: GetFindingsUseCase,
) {
    @POST
    @Path("/check/trigger")
    @RolesAllowed("ROLE_ADMIN")
    fun triggerCheck(): AuthzPolicyReport = runBlocking {
        runCheck.run(RunTrigger.OPERATOR_MANUAL)
    }

    @GET
    @Path("/findings")
    @RolesAllowed("ROLE_ADMIN", "ROLE_VIEWER")
    suspend fun getActiveFindings(): List<AuthzPolicyFinding> = getFindings.getActive()

    @GET
    @Path("/findings/{id}")
    @RolesAllowed("ROLE_ADMIN", "ROLE_VIEWER")
    suspend fun getFinding(@PathParam("id") id: String): AuthzPolicyFinding =
        getFindings.getById(id) ?: throw NotFoundException("Finding $id not found")
}
