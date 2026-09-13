// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.sanctions.application.usecase.SanctionsListService
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/**
 * v2 of the refresh-all trigger (#9048, ADR-0048): the v1 operation answered 200 only after every
 * enabled feed had been re-downloaded inside the request — a synchronous external fan-out that
 * outlived every proxy timeout. The v2 contract is honest about the work being deferred: 202
 * immediately, the imports run on the scheduler one list at a time, and per-list progress is
 * observable through each list's `lastUpdatedAt` on GET /api/v1/sanctions/lists.
 */
@Path("/api/v2/sanctions/lists")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class SanctionsListRefreshV2Resource(private val service: SanctionsListService) {

    @POST
    @Path("/refresh-all")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "sanctions.trigger", resource = "")
    suspend fun refreshAll(@HeaderParam("Idempotency-Key") idempotencyKey: String?): Response {
        // #8351: money-path POSTs require the key. Nullable + requireNotNull in the BODY — a
        // non-nullable JAX-RS param NPEs at injection (a 500), and a suspend fun emits no
        // intrinsic at all (the null flows in silently). libs-runtime maps this to 400.
        // No dedup store is needed: flagging is `UPDATE ... SET refresh_requested_at = now`,
        // idempotent by construction — a replayed key converges to the same state.
        requireNotNull(idempotencyKey) { "Idempotency-Key header is required" }
        return Response.accepted(mapOf("requested" to service.requestRefreshAll())).build()
    }
}
