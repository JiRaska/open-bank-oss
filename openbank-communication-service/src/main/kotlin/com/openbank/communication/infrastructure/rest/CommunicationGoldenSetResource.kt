// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.communication.infrastructure.rest

import com.openbank.communication.application.CommunicationGoldenSetService
import com.openbank.communication.application.CreateGoldenSetEntryCommand
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.Response
import java.util.UUID

data class CreateGoldenSetEntryRequest(
    val question: String,
    val expectedLanguage: String,
    val expectNoFigureFromMemory: Boolean = false,
    // Nullable elements on purpose — same reasoning as DraftPlaybookVersionRequest's list
    // fields (issue #7867): a null array element deserialises fine and NPEs at first
    // dereference unless the declared type says it can happen and a guard turns that into a 400.
    val expectedToneMarkers: List<String?> = emptyList(),
    val requiredComplianceSentence: String? = null,
) {
    fun validatedToneMarkers(): List<String> =
        expectedToneMarkers.mapIndexed { i, m -> requireNotNull(m) { "expectedToneMarkers[$i] must not be null" } }
}

/**
 * D4's golden-set editor surface — CRUD only, no publish/replay gate (see
 * `CommunicationGoldenSetService`'s KDoc for why). Reuses `ROLE_COMMS_EDITOR` since authoring a
 * golden-set entry carries the same risk profile as drafting: it is inert data an editor writes,
 * not customer-facing content, and nothing here reaches a customer without a later, separate
 * change wiring the replay engine.
 */
@Path("/api/v1/personas")
@ApplicationScoped
class CommunicationGoldenSetResource(
    private val service: CommunicationGoldenSetService,
    private val identity: SecurityIdentity,
) {
    private fun actor() = identity.principal.name

    @POST
    @Path("/{personaKey}/golden-set")
    @RolesAllowed("ROLE_COMMS_EDITOR", "ROLE_ADMIN")
    suspend fun create(@PathParam("personaKey") personaKey: String, req: CreateGoldenSetEntryRequest): Response =
        Response.status(Response.Status.CREATED).entity(
            service.create(
                CreateGoldenSetEntryCommand(
                    personaKey = personaKey,
                    question = req.question,
                    expectedLanguage = req.expectedLanguage,
                    expectNoFigureFromMemory = req.expectNoFigureFromMemory,
                    expectedToneMarkers = req.validatedToneMarkers(),
                    requiredComplianceSentence = req.requiredComplianceSentence,
                    createdBy = actor(),
                ),
            ),
        ).build()

    @GET
    @Path("/{personaKey}/golden-set")
    @RolesAllowed("ROLE_COMMS_EDITOR", "ROLE_COMMS_APPROVER", "ROLE_ADMIN")
    suspend fun list(@PathParam("personaKey") personaKey: String): Response =
        Response.ok(service.list(personaKey)).build()

    @DELETE
    @Path("/golden-set/{id}")
    @RolesAllowed("ROLE_COMMS_EDITOR", "ROLE_ADMIN")
    suspend fun delete(@PathParam("id") id: UUID): Response {
        service.delete(id, actor())
        return Response.noContent().build()
    }
}
