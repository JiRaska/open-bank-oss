// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.communication.infrastructure.rest

import com.openbank.communication.application.CommunicationStyleService
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.Response

/** Keeps the v1 published-copy read available to existing service consumers. */
@Path("/api/v1/personas")
@ApplicationScoped
class PublishedStyleV1Resource(private val service: CommunicationStyleService) {
    @GET
    @Path("/{personaKey}/published")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMMS_EDITOR", "ROLE_COMMS_APPROVER")
    suspend fun published(@PathParam("personaKey") personaKey: String): Response {
        val style = service.published(personaKey)
        return Response.ok(style).tag(style.styleVersion.toString()).build()
    }
}
