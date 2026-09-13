// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.communication.infrastructure.rest

import com.openbank.communication.application.CommunicationPlaybookService
import com.openbank.communication.application.DraftPlaybookVersionCommand
import com.openbank.communication.domain.ApprovedAnswer
import com.openbank.communication.domain.CallScriptStep
import com.openbank.libs.authz.Authorize
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.Response
import java.util.UUID

data class DraftPlaybookVersionRequest(
    // Nullable elements on purpose — same reasoning as DraftStyleVersionRequest's map/list
    // fields (issue #7867): a null array element deserialises fine and NPEs at first dereference
    // unless the declared type says it can happen and a guard turns that into a 400.
    val callScript: List<CallScriptStep?> = emptyList(),
    val approvedAnswers: List<ApprovedAnswer?> = emptyList(),
) {
    fun validatedCallScript(): List<CallScriptStep> =
        callScript.mapIndexed { i, s -> requireNotNull(s) { "callScript[$i] must not be null" } }

    fun validatedApprovedAnswers(): List<ApprovedAnswer> =
        approvedAnswers.mapIndexed { i, a -> requireNotNull(a) { "approvedAnswers[$i] must not be null" } }
}

/**
 * ADR-0285 D7's playbook editor surface + agent-assist retrieval. Mirrors
 * `CommunicationStyleResource`'s role/four-eyes shape exactly — same `ROLE_COMMS_EDITOR`/
 * `ROLE_COMMS_APPROVER` split, same `commstyle.publish` action (D3's own text: "publishing a
 * style OR playbook version" — one action, both content types).
 */
@Path("/api/v1/personas")
@ApplicationScoped
class CommunicationPlaybookResource(
    private val service: CommunicationPlaybookService,
    private val identity: SecurityIdentity,
) {
    private fun actor() = identity.principal.name

    @POST
    @Path("/{personaKey}/playbook-versions")
    @RolesAllowed("ROLE_COMMS_EDITOR", "ROLE_ADMIN")
    suspend fun draft(@PathParam("personaKey") personaKey: String, req: DraftPlaybookVersionRequest): Response =
        Response.status(Response.Status.CREATED).entity(
            service.draft(
                DraftPlaybookVersionCommand(
                    personaKey = personaKey,
                    callScript = req.validatedCallScript(),
                    approvedAnswers = req.validatedApprovedAnswers(),
                    maker = actor(),
                ),
            ),
        ).build()

    @POST
    @Path("/playbook-versions/{id}/submit")
    @RolesAllowed("ROLE_COMMS_EDITOR", "ROLE_ADMIN")
    suspend fun submit(@PathParam("id") id: UUID): Response = Response.ok(service.submit(id, actor())).build()

    @POST
    @Path("/playbook-versions/{id}/publish")
    @RolesAllowed("ROLE_COMMS_APPROVER", "ROLE_ADMIN")
    @Authorize(action = "commstyle.publish", resource = "#id")
    suspend fun publish(@PathParam("id") id: UUID): Response = Response.ok(service.publish(id, actor())).build()

    @POST
    @Path("/playbook-versions/{id}/retire")
    @RolesAllowed("ROLE_COMMS_APPROVER", "ROLE_ADMIN")
    suspend fun retire(@PathParam("id") id: UUID): Response = Response.ok(service.retire(id, actor())).build()

    @GET
    @Path("/{personaKey}/playbook/published")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    suspend fun published(@PathParam("personaKey") personaKey: String): Response {
        val playbook = service.published(personaKey)
        return Response.ok(playbook).tag(playbook.playbookVersion.toString()).build()
    }

    /**
     * The agent-assist retrieval endpoint (D2/D7) — a contact-centre agent (or `ui-assistant` on
     * their behalf) searches approved answers for the situation in front of them. `ROLE_OPERATOR`
     * on top of `ROLE_API`: a human staff member is the direct caller here, not only M2M.
     */
    @GET
    @Path("/{personaKey}/playbook/search")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    suspend fun search(
        @PathParam("personaKey") personaKey: String,
        @QueryParam("q") query: String?,
        @QueryParam("limit") @DefaultValue("5") limit: Int,
    ): Response {
        val q = requireNotNull(query) { "query parameter 'q' is required" }
        return Response.ok(service.searchApprovedAnswers(personaKey, q, limit.coerceIn(1, MAX_SEARCH_LIMIT))).build()
    }

    private companion object {
        const val MAX_SEARCH_LIMIT = 20
    }
}
