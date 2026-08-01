// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.rest

import com.openbank.campaign.application.usecase.SegmentQuery
import com.openbank.libs.authz.Authorize
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.Response

/**
 * Read-only segment catalogue for the operator console (#2895).
 *
 * There is no POST here on purpose. ADR-0201 D1 makes a segment a versioned artifact defined in
 * code; an authoring endpoint would reintroduce "free-form definitions from a UI" with extra steps.
 * A new segment is a pull request against `SegmentCatalog`.
 */
@Path("/api/v1/segments")
@ApplicationScoped
class SegmentResource(private val query: SegmentQuery) {

    /** Every targetable segment, with its version and what it selects, in words. */
    @GET
    @Authorize(action = "campaign.read", resource = "#none")
    suspend fun list(): Response = Response.ok(query.list()).build()

    /**
     * How many parties this segment matches right now.
     *
     * Runs the same evaluation enrolment runs — a preview computed a different way would be a
     * number that agrees with the send only by luck, which is the failure ADR-0201 D1 names.
     */
    @GET
    @Path("/{name}/{version}/preview")
    @Authorize(action = "campaign.read", resource = "#name")
    suspend fun preview(@PathParam("name") name: String, @PathParam("version") version: Int): Response =
        query.preview(name, version)
            ?.let { Response.ok(it).build() }
            ?: Response.status(Response.Status.NOT_FOUND)
                .entity(mapOf("error" to "unknown segment $name@$version"))
                .build()
}
