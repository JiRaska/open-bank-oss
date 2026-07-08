// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.rest

import com.openbank.billing.application.usecase.BillingCycleService
import com.openbank.billing.application.usecase.FeeAssessmentService
import com.openbank.libs.authz.Authorize
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/**
 * Fee assessment + posting endpoints (ADR-0143). `POST /api/v1/fees/assess` **computes** an
 * account's fee assessment for a cycle from live account/balance/catalog reads and returns it —
 * it does **not** post anything to the ledger (a dry run, unchanged from phase 2c read-path).
 *
 * `action = "billing.read"` (not `"billing.assess"`) is deliberate (ADR-0034 D5): naming it a
 * `.read` verb lets it fall under the existing generic `operator-read-any` / `compliance-read-any`
 * rest.rego rules with no new policy needed, and correctly keeps it OUT of the four-eyes gate
 * (`rules.yaml: four_eyes.verbs` doesn't include `read`) — matching the fact that this endpoint
 * cannot move money.
 *
 * `POST /api/v1/fees/post` (phase 2c-ii) is the money-moving twin: it persists the assessment AND
 * commits the atomic intent-to-post (outbox row) for every chargeable fee — the outbox dispatcher
 * then actually calls the ledger. `action = "billing.post"`, **not** the ADR's literal
 * `"ledger.post"` text — `rest.rego`'s `money_path_scopes` derives the four-eyes scope from
 * `rules.yaml: money_path_services` by stripping the `openbank-`/`-service` fixings
 * (`openbank-billing-service` -> `billing`), and billing has no
 * `money_path_action_prefixes` override, so only an action literally prefixed `billing.` can ever
 * match. `ledger.post` would silently evaluate against `ledger`'s own scope (a different service)
 * and NEVER flag `four_eyes_required` for this endpoint — the opposite of the ADR's intent. See
 * `openbank-libs/governance/policies/rest.rego` (`money_path_scopes`, `four_eyes_required`) and
 * `rules.yaml: four_eyes.verbs` (`post` is listed). `postedBy` is the JWT `sub` (ADR-0143 step 4),
 * read from [SecurityIdentity] rather than `@Context SecurityContext` because this is a
 * `suspend fun` (mirrors `AccountResource.operatorId()`).
 */
@ApplicationScoped
@Path("/api/v1/fees")
@Produces(MediaType.APPLICATION_JSON)
class BillingResource(private val service: FeeAssessmentService, private val cycleService: BillingCycleService) {

    @Inject
    lateinit var identity: SecurityIdentity

    @POST
    @Path("/assess")
    @Authenticated
    @Authorize(action = "billing.read", resource = "#accountId")
    suspend fun assess(
        @QueryParam("cycleId") cycleId: String?,
        @QueryParam("accountId") accountId: String?,
        @QueryParam("currency") currency: String?,
    ): Response {
        val validation = validateParams(cycleId, accountId, currency) ?: return badRequest()
        val (c, a, cur) = validation
        return Response.ok(service.assess(c, a, cur)).build()
    }

    @POST
    @Path("/post")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "billing.post", resource = "#accountId")
    suspend fun post(
        @QueryParam("cycleId") cycleId: String?,
        @QueryParam("accountId") accountId: String?,
        @QueryParam("currency") currency: String?,
    ): Response {
        val validation = validateParams(cycleId, accountId, currency) ?: return badRequest()
        val (c, a, cur) = validation
        // ADR-0143 step 4: postedBy is captured for the audit trail; the ledger-level maker/checker
        // separation is enforced by AuthorizeInterceptor's four-eyes gate on this action, keyed on
        // the same JWT sub — see the class KDoc.
        val postedBy = postedBy()
        val assessment = cycleService.assessAndPost(c, a, cur)
        return Response.ok(assessment).header("X-Posted-By", postedBy).build()
    }

    private fun validateParams(
        cycleId: String?,
        accountId: String?,
        currency: String?,
    ): Triple<String, String, String>? {
        if (cycleId.isNullOrBlank() || accountId.isNullOrBlank() || currency.isNullOrBlank()) return null
        return Triple(cycleId, accountId, currency)
    }

    private fun badRequest(): Response = Response.status(Response.Status.BAD_REQUEST)
        .entity(mapOf("error" to "cycleId, accountId and currency are required"))
        .build()

    // .principal.name (preferred_username), NOT .subject (UUID) — MUST match how
    // AuthorizeInterceptor.buildQuery resolves the maker's Principal.id (sc.userPrincipal?.name),
    // or the four-eyes ApprovalStore's makerId comparison (see ApprovalResource.checkerId()) would
    // compare differently-formatted ids for the same person and could fail to catch (or wrongly
    // flag) a self-approval.
    private fun postedBy(): String = identity.principal?.name ?: "anonymous"
}
