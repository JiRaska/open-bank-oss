// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.rest

import com.openbank.account.application.port.`in`.AuthorizationUseCase
import com.openbank.account.application.port.`in`.DelegatedPaymentOutcome
import com.openbank.libs.authz.Authorize
import com.openbank.libs.domain.money.Money
import com.openbank.libs.security.Roles
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import java.math.BigDecimal
import java.util.UUID

/**
 * The debit-authorization question the payment path asks before letting a NON-OWNER move money
 * out of a shared account (ADR-0232 D3/D5, #2990 AC9/AC10).
 *
 * **Why the decision lives here and not in the caller.** account-service is the only service
 * holding the delegation enforcement projection (`DelegationEventConsumer` feeds it from
 * `openbank.delegation.events`) *and* the account's true owner. Any caller that answered this
 * itself would need both, i.e. a second copy of the projection kept in step with this one — the
 * failure mode this repo has been burned by repeatedly, where two copies of a rule drift and the
 * money-path copy is the stale one. Exposing it as an endpoint is also the shape the fleet
 * already uses for exactly this: `AuthorizationResource./check`, `SavingsGoalDelegationResource`
 * and the card `/check` all answer a delegation question for a caller that does not hold the
 * projection.
 *
 * **Why a separate endpoint from `AuthorizationResource./check`.** That one answers a boolean
 * about a role. This one has to answer with the grant id and the grantor, because a delegated
 * debit is only auditable *as delegated* if the record names the grant that permitted it — and
 * the grant is revocable, so nothing after the fact can reconstruct which one was live. Widening
 * `/check` would have changed a response other callers already parse.
 *
 * **Role/action gate.** `account.read` and the edge-proxy role set, identical to
 * [SavingsGoalDelegationResource] — deliberately reusing an existing OPA action rather than
 * minting one, so no `rest.rego` change (and no 65-file bundle restamp) rides along with a money
 * -path behaviour change. `partyId` is a query parameter and NOT the `X-Customer-Party-Id`
 * header: the header is the fleet's *ownership* guard and would 404 the very delegate this
 * endpoint exists to answer about. The caller is a service (the edge M2M identity), and the
 * subject it asks about must come from that caller's own authenticated-identity resolution —
 * never from a customer request body. See the KDoc on the edge route for that half.
 */
@Path("/api/v1/accounts/{accountId}/delegation/payment-authorization")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class DelegatedPaymentAuthorizationResource(private val authorizationUseCase: AuthorizationUseCase) {

    /**
     * Whether [partyId] may initiate a debit of [amount] [currency] from [accountId].
     *
     * `amount`/`currency` are optional: omitting them asks the capability question without the
     * ceiling, which is what a pre-flight ("can this person pay from here at all?") wants. A
     * payment about to be submitted MUST pass both, or the per-transaction limit is not evaluated.
     *
     * Response:
     * ```json
     * { "authorized": true, "outcome": "DELEGATED",
     *   "delegationId": "<uuid>", "grantorPartyId": "<uuid>" }
     * ```
     * `delegationId`/`grantorPartyId` are present only when `outcome` is `DELEGATED`. `outcome`
     * distinguishes NO_GRANT from LIMIT_EXCEEDED for the audit record; the caller is expected to
     * render every non-authorized outcome as the same opaque refusal on the customer wire, so the
     * endpoint does not become an existence oracle for other people's grants.
     */
    @GET
    @RolesAllowed(Roles.API, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "account.read", resource = "#accountId")
    @Operation(summary = "Whether a party may initiate a payment of a given amount from the account")
    suspend fun check(
        @PathParam("accountId") accountId: UUID,
        @QueryParam("partyId") partyId: UUID?,
        @QueryParam("amount") amount: String?,
        @QueryParam("currency") currency: String?,
    ): Response {
        // #3104 — required, and absent it used to reach the use case as null and answer 500. This
        // handler is `suspend`, so no Kotlin intrinsic fires at the boundary and the null would
        // flow into the body; libs-runtime maps this guard to 400.
        requireNotNull(partyId) { "query parameter 'partyId' is required" }
        val money = when {
            amount.isNullOrBlank() -> null
            currency.isNullOrBlank() -> return badRequest("currency is required when amount is supplied")
            // Money's own invariant rejects an over-scaled amount for the currency, and CurrencyCode
            // rejects an unknown code. Both are caller errors, not refusals: collapsing them into
            // `authorized:false` would let a malformed request read as a policy decision.
            else -> runCatching { Money.of(BigDecimal(amount), currency) }.getOrNull()
                ?: return badRequest("amount/currency is not a valid monetary value")
        }
        val decision = authorizationUseCase.authorizeDelegatedPayment(accountId, partyId, money)
        val body = buildMap<String, Any?> {
            put("authorized", decision.authorized)
            put("outcome", decision.outcome.name)
            if (decision.outcome == DelegatedPaymentOutcome.DELEGATED) {
                put("delegationId", decision.delegationId?.toString())
                put("grantorPartyId", decision.grantorPartyId?.toString())
            }
        }
        return Response.ok(body).build()
    }

    private fun badRequest(message: String): Response =
        Response.status(Response.Status.BAD_REQUEST).entity(mapOf("error" to message)).build()
}
