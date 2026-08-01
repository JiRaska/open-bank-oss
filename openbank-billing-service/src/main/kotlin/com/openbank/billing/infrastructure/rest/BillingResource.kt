// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.rest

import com.openbank.billing.application.usecase.BillingCycleService
import com.openbank.billing.application.usecase.FeeAssessmentService
import com.openbank.billing.application.usecase.FeeNotFoundException
import com.openbank.billing.application.usecase.FeeNotPostedException
import com.openbank.billing.application.usecase.FeeReversalService
import com.openbank.libs.authz.Authorize
import com.openbank.libs.domain.money.CurrencyCode
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
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
 *
 * `POST /api/v1/fees/reverse` (ADR-0143 phase 2e) reverses an already-POSTED fee: `action =
 * "billing.reverse"` — `reverse` is already a registered `rules.yaml: four_eyes.verbs` entry, so
 * this gets `four_eyes_required` from the SAME `rest.rego` rule as `billing.post`, no policy change
 * needed. Reuses the identical `AuthorizeInterceptor` + `ApprovalStore` (ADR-0155) four-eyes
 * infrastructure as the charge path — a maker's call is paused (202 + approval id) until a
 * DIFFERENT operator decides it via [ApprovalResource], exactly like `billing.post`. Deliberately
 * does NOT call ledger-service's own `POST /journals/{id}/reverse` (which is itself four-eyes
 * gated at the ledger's principal, `ledger.reverse`) — a service-to-service caller has no distinct
 * human "checker" to decide that second gate, so it would orphan a pending approval forever;
 * billing posts its own compensating journal (`LedgerPostingAdapter.postReversal`) via the plain
 * `POST /journals` contract instead, keeping the single human dual-control point at this endpoint.
 */
@ApplicationScoped
@Path("/api/v1/fees")
@Produces(MediaType.APPLICATION_JSON)
class BillingResource(
    private val service: FeeAssessmentService,
    private val cycleService: BillingCycleService,
    private val reversalService: FeeReversalService,
) {

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
        val params = when (val v = validateParams(cycleId, accountId, currency)) {
            is ParamValidation.Invalid -> return badRequest(v.message)
            is ParamValidation.Valid -> v
        }
        return Response.ok(service.assess(params.cycleId, params.accountId, params.currency)).build()
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
        val params = when (val v = validateParams(cycleId, accountId, currency)) {
            is ParamValidation.Invalid -> return badRequest(v.message)
            is ParamValidation.Valid -> v
        }
        // ADR-0143 step 4: postedBy is captured for the audit trail; the ledger-level maker/checker
        // separation is enforced by AuthorizeInterceptor's four-eyes gate on this action, keyed on
        // the same JWT sub — see the class KDoc.
        val postedBy = postedBy()
        val assessment = cycleService.assessAndPost(params.cycleId, params.accountId, params.currency)
        return Response.ok(assessment).header("X-Posted-By", postedBy).build()
    }

    @POST
    @Path("/reverse")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "billing.reverse", resource = "#idempotencyKey")
    suspend fun reverse(@QueryParam("idempotencyKey") idempotencyKey: String?, request: ReverseFeeRequest?): Response {
        val reason = request?.reason
        if (idempotencyKey.isNullOrBlank() || reason.isNullOrBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(mapOf("error" to "idempotencyKey (query) and reason (body) are required"))
                .build()
        }
        val reversedBy = postedBy()
        return try {
            val fee = reversalService.reverse(idempotencyKey, reason)
            Response.ok(fee).header("X-Reversed-By", reversedBy).build()
        } catch (e: FeeNotFoundException) {
            Response.status(Response.Status.NOT_FOUND).entity(mapOf("error" to e.message)).build()
        } catch (e: FeeNotPostedException) {
            Response.status(Response.Status.CONFLICT).entity(mapOf("error" to e.message)).build()
        }
    }

    /**
     * Validates the three query parameters against the constraints the *database* enforces, which
     * until #3038 were the only place they were enforced at all.
     *
     * Blankness was the sole check here, so any non-blank garbage reached
     * `BillingAssessmentRepository.persistWithPostingIntent` and died at the schema
     * (`V1__init_billing.sql`: `currency CHAR(3) NOT NULL`, `cycle_id`/`account_id VARCHAR(64)`)
     * as an unmapped Postgres error — a 500 for what is squarely a client error. The authenticated
     * fuzz run found it with `currency=ISO-2022-CN` (11 characters).
     *
     * Note the fault-tolerant path *below* this one does not save us: an unresolvable account
     * yields a `skipped = true` assessment that still carries the caller's raw currency, and
     * `BillingCycleService.assessAndPost` persists skipped assessments too — so the graceful
     * degradation branch is precisely the branch that reaches the `CHAR(3)` column.
     *
     * [CurrencyCode.of] also upper-cases, deliberately: `czk` and `CZK` would otherwise be two
     * distinct values in both the column and the `(cycleId, accountId, currency)` idempotency key,
     * so the same fee could be assessed twice under two spellings of one currency.
     */
    private fun validateParams(
        cycleId: String?,
        accountId: String?,
        currency: String?,
    ): ParamValidation {
        if (cycleId.isNullOrBlank() || accountId.isNullOrBlank() || currency.isNullOrBlank()) {
            return ParamValidation.Invalid("cycleId, accountId and currency are required")
        }
        if (cycleId.length > ID_MAX_LENGTH) {
            return ParamValidation.Invalid("cycleId must be at most $ID_MAX_LENGTH characters")
        }
        if (accountId.length > ID_MAX_LENGTH) {
            return ParamValidation.Invalid("accountId must be at most $ID_MAX_LENGTH characters")
        }
        val normalisedCurrency = try {
            CurrencyCode.of(currency).code
        } catch (e: IllegalArgumentException) {
            // CurrencyCode rejects both a wrong length and an unknown ISO 4217 code — but its
            // `defaultFractionDigits` property initialiser runs BEFORE its `init` block, so the
            // throw usually comes from java.util.Currency.getInstance, which carries a NULL
            // message. The offending value therefore has to come from here, not from the cause.
            val detail = e.message ?: "not a known ISO 4217 code"
            return ParamValidation.Invalid("currency '$currency' is invalid: $detail")
        }
        return ParamValidation.Valid(cycleId, accountId, normalisedCurrency)
    }

    private fun badRequest(message: String): Response = Response.status(Response.Status.BAD_REQUEST)
        .entity(mapOf("error" to message))
        .build()

    // .principal.name (preferred_username), NOT .subject (UUID) — MUST match how
    // AuthorizeInterceptor.buildQuery resolves the maker's Principal.id (sc.userPrincipal?.name),
    // or the four-eyes ApprovalStore's makerId comparison (see ApprovalResource.checkerId()) would
    // compare differently-formatted ids for the same person and could fail to catch (or wrongly
    // flag) a self-approval.
    private fun postedBy(): String = identity.principal?.name ?: "anonymous"

    private companion object {
        /** Matches `cycle_id`/`account_id VARCHAR(64)` in `V1__init_billing.sql`. */
        const val ID_MAX_LENGTH = 64
    }
}

/** Outcome of [BillingResource]'s query-parameter validation — a valid triple, or why it is not. */
private sealed interface ParamValidation {
    data class Valid(val cycleId: String, val accountId: String, val currency: String) : ParamValidation
    data class Invalid(val message: String) : ParamValidation
}

data class ReverseFeeRequest(val reason: String)
