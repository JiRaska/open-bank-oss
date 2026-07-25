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
import io.smallrye.common.annotation.Blocking
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
    // @Blocking: starts a Temporal workflow and waits for it synchronously — must run on a worker
    // thread, not the event loop. It touches no reactive DB session, so runBlocking is fine here.
    @POST
    @Path("/check/trigger")
    @RolesAllowed("ROLE_ADMIN")
    @Blocking
    fun triggerCheck(): DocsTruthReport = runBlocking {
        runCheck.run(RunTrigger.OPERATOR_MANUAL)
    }

    // suspend: the reads hit reactive Panache, which needs a Vert.x context — RESTEasy Reactive runs
    // Kotlin suspend resource methods on one, so the session resolves correctly.
    @GET
    @Path("/findings")
    @RolesAllowed("ROLE_ADMIN", "ROLE_VIEWER")
    suspend fun getActiveFindings(): List<DocsTruthFinding> = getFindings.getActive()

    @GET
    @Path("/findings/{id}")
    @RolesAllowed("ROLE_ADMIN", "ROLE_VIEWER")
    suspend fun getFinding(@PathParam("id") id: String): DocsTruthFinding =
        getFindings.getById(id) ?: throw NotFoundException("Finding $id not found")
}
