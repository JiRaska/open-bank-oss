// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.infrastructure.rest

import com.openbank.engagement.application.usecase.ReadAdverseStateUseCase
import com.openbank.libs.authz.Authorize
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.Response
import java.util.UUID

/**
 * The operator-facing read of a party's active adverse states (issue #4265 item 1) — the missing
 * half of ADR-0220 D3.5. The signals have been durable in `party_adverse_state` and queryable via
 * `AdverseStateRepository.activeStates` since that table landed; nothing exposed them, so a
 * support agent fielding "why did I stop getting offers" had no on-screen answer and no way to
 * spot-check the suppression short of a database session.
 *
 * SEPARATE from [SurfaceResource] on purpose, and not merely for tidiness: the surface API is the
 * customer-app plane reached through the customer edge (`edge-service-engagement` in
 * `openbank-libs/governance/policies/rest.rego`, scoped to exactly two actions), while this is the
 * staff plane. Hanging an operator read off `/api/v1/surfaces` would have put it one careless
 * `input.action` widening away from the edge's grant. Hence a different path, a different action,
 * and no `ROLE_API`.
 *
 * Authorization needs NO new rego rule and deliberately so: `engagement.adverseState.read` ends in
 * `.read`, which `operator-read-any` (ROLE_OPERATOR/ROLE_ADMIN) and `compliance-read-any`
 * (ROLE_COMPLIANCE) already grant in the shared policy. Every role in the `@RolesAllowed` below
 * therefore has a matching OPA reason under enforcement — verified by reading those two rules, not
 * assumed from the annotation. A new bespoke action name would instead have hit
 * `default allow := false` and shipped an endpoint that 403s for everyone while looking wired.
 *
 * ADR-0210 D3's non-authoritative posture is intact by construction: the row behind this response
 * is (party id, flag name, timestamp). No balance, no transaction row, no KYC content is newly
 * reachable, so this is not the drive-by expansion D3 warns about.
 */
@Path("/api/v1/eligibility/adverse-states")
@ApplicationScoped
class AdverseStateResource(private val read: ReadAdverseStateUseCase) {

    /**
     * `partyId` is declared NULLABLE, and that is load-bearing rather than defensive. JAX-RS
     * injects `null` for an absent query parameter; a non-nullable `UUID` here would make the
     * absent-header case a 500 (`GenericExceptionMapper`), and in a `suspend fun` — which emits no
     * `Intrinsics.checkNotNullParameter` at all — the null would instead flow silently into the
     * body. `requireNotNull` only does anything because the declared type admits null; libs-runtime
     * maps the resulting `IllegalArgumentException` to 400, so no service-local mapper is added.
     */
    @GET
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE")
    @Authorize(action = "engagement.adverseState.read", resource = "#partyId")
    suspend fun activeStates(@QueryParam("partyId") partyId: UUID?): Response {
        requireNotNull(partyId) { "query parameter 'partyId' is required" }
        return Response.ok(
            mapOf(
                "partyId" to partyId.toString(),
                "adverseStates" to read.activeStates(partyId).map { it.name },
            ),
        ).build()
    }
}
