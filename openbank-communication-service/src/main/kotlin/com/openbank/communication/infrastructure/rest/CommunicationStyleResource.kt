// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.communication.infrastructure.rest

import com.openbank.communication.application.CommunicationStyleService
import com.openbank.communication.application.DraftStyleVersionCommand
import com.openbank.libs.authz.Authorize
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.Response
import java.util.UUID

data class DraftStyleVersionRequest(
    val tone: String,
    val formality: String,
    val formOfAddress: String,
    val maxLength: Int? = null,
    // Nullable element/value types on purpose: a null array element or map value deserialises
    // fine (Jackson doesn't reject it) even though the Kotlin type says it cannot happen — the
    // declared type only decides where the failure lands, and every landing is a 500 unless the
    // element is declared nullable and guarded explicitly (issue #7867). `requireNotNull` below
    // turns that 500 into a 400 naming the offending key/index.
    val preferredTerms: Map<String, String?> = emptyMap(),
    val forbiddenTerms: List<String?> = emptyList(),
    val signature: String? = null,
) {
    /** Validates every element is non-null and returns the caller-facing non-nullable shape. */
    fun validatedPreferredTerms(): Map<String, String> =
        preferredTerms.mapValues { (k, v) -> requireNotNull(v) { "preferredTerms['$k'] must not be null" } }

    fun validatedForbiddenTerms(): List<String> =
        forbiddenTerms.mapIndexed { i, v -> requireNotNull(v) { "forbiddenTerms[$i] must not be null" } }
}

/**
 * ADR-0285 D7's style editor surface. `commstyle.draft`/`commstyle.submit` carry
 * `ROLE_COMMS_EDITOR` (D6's maker role); `commstyle.publish`/`commstyle.retire` carry
 * `ROLE_COMMS_APPROVER` (D6's checker role) AND are four-eyes-gated (D3) — the second control
 * on top of the role check, so a lone `ROLE_COMMS_APPROVER` cannot publish their own draft.
 */
@Path("/api/v1/personas")
@ApplicationScoped
class CommunicationStyleResource(
    private val service: CommunicationStyleService,
    private val identity: SecurityIdentity,
) {
    private fun actor() = identity.principal.name

    @POST
    @Path("/{personaKey}/style-versions")
    @RolesAllowed("ROLE_COMMS_EDITOR", "ROLE_ADMIN")
    suspend fun draft(@PathParam("personaKey") personaKey: String, req: DraftStyleVersionRequest): Response =
        Response.status(Response.Status.CREATED).entity(
            service.draft(
                DraftStyleVersionCommand(
                    personaKey = personaKey,
                    tone = req.tone,
                    formality = req.formality,
                    formOfAddress = req.formOfAddress,
                    maxLength = req.maxLength,
                    preferredTerms = req.validatedPreferredTerms(),
                    forbiddenTerms = req.validatedForbiddenTerms(),
                    signature = req.signature,
                    maker = actor(),
                ),
            ),
        ).build()

    @POST
    @Path("/style-versions/{id}/submit")
    @RolesAllowed("ROLE_COMMS_EDITOR", "ROLE_ADMIN")
    suspend fun submit(@PathParam("id") id: UUID): Response = Response.ok(service.submit(id, actor())).build()

    @POST
    @Path("/style-versions/{id}/publish")
    @RolesAllowed("ROLE_COMMS_APPROVER", "ROLE_ADMIN")
    @Authorize(action = "commstyle.publish", resource = "#id")
    suspend fun publish(@PathParam("id") id: UUID): Response = Response.ok(service.publish(id, actor())).build()

    @POST
    @Path("/style-versions/{id}/retire")
    @RolesAllowed("ROLE_COMMS_APPROVER", "ROLE_ADMIN")
    suspend fun retire(@PathParam("id") id: UUID): Response = Response.ok(service.retire(id, actor())).build()

    /**
     * D5's consumer-facing read. `ROLE_API` is the fleet-wide grant every service-account M2M
     * caller carries — NOT `ROLE_SERVICE` (`input.principal.type == "SERVICE"` never fires: a
     * Keycloak client_credentials token classifies as HUMAN, no realm client is ever granted
     * `ROLE_SERVICE`, per `check-no-service-principal-type.sh`). `ETag` lets a consumer's
     * short-TTL cache send `If-None-Match` and skip the body on an unchanged version (D5's
     * cache-and-fall-back consumer shape).
     */
    @GET
    @Path("/{personaKey}/published")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    suspend fun published(@PathParam("personaKey") personaKey: String): Response {
        val style = service.published(personaKey)
        return Response.ok(style).tag(style.styleVersion.toString()).build()
    }
}
