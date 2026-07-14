// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.docstruth.infrastructure.rest

import com.openbank.docstruth.application.port.incoming.GetFindingsUseCase
import com.openbank.docstruth.application.port.incoming.RunDocsTruthCheckUseCase
import com.openbank.docstruth.domain.model.DocsTruthFinding
import com.openbank.docstruth.domain.model.DocsTruthReport
import com.openbank.docstruth.domain.model.RunTrigger
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import kotlinx.coroutines.runBlocking

@Path("/api/v1/docs-truth-agent")
@Produces(MediaType.APPLICATION_JSON)
class DocsTruthResource(private val runCheck: RunDocsTruthCheckUseCase, private val getFindings: GetFindingsUseCase) {
    @POST
    @Path("/check/trigger")
    @RolesAllowed("platform-admin")
    fun triggerCheck(): DocsTruthReport = runBlocking {
        runCheck.run(RunTrigger.OPERATOR_MANUAL)
    }

    @GET
    @Path("/findings")
    @RolesAllowed("platform-admin", "platform-viewer")
    fun getActiveFindings(): List<DocsTruthFinding> = runBlocking {
        getFindings.getActive()
    }

    @GET
    @Path("/findings/{id}")
    @RolesAllowed("platform-admin", "platform-viewer")
    fun getFinding(@PathParam("id") id: String): DocsTruthFinding = runBlocking {
        getFindings.getById(id) ?: throw NotFoundException("Finding $id not found")
    }
}
