// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.infrastructure.rest

import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

/**
 * Placeholder REST surface for the case-coordinator agent (ADR-0244).
 * Exposes a health/readiness probe path and a simple info endpoint so the module
 * boots, registers its OpenAPI contract, and passes the boot smoke test.
 */
@Path("/api/v1/case-coordinator")
@Produces(MediaType.APPLICATION_JSON)
class CaseCoordinatorResource {

    data class Status(val service: String, val status: String)

    @GET
    @Path("/status")
    @RolesAllowed("ROLE_ADMIN", "ROLE_VIEWER")
    fun status(): Status = Status(service = "case-coordinator-agent", status = "up")
}
