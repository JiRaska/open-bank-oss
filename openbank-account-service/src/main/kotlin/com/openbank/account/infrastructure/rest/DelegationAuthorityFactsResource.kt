// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.rest

import com.openbank.account.application.port.`in`.AuthorizationUseCase
import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.Operation
import java.util.UUID

/**
 * The grant facts a payment rail needs to answer `AdmissionStep.AUTHORITY` itself (ADR-0375 D1).
 *
 * **Why this exists next to `DelegatedPaymentAuthorizationResource`, which answers the whole
 * question.** That endpoint hard-codes `PAYMENT_ONLY` and returns a verdict, which is exactly
 * right for the edge pre-flight it was built for and structurally wrong for the admission chain.
 * The chain's authority step is a pure function whose *capability is an argument* — that is the
 * defect ADR-0375 D7 was opened for, where two card capabilities were each gated by their
 * neighbour because a nearby capability was close enough. A rail that receives a
 * verdict cannot ask about the capability its own action names; a rail that receives the facts can.
 *
 * **The owner travels with the grants.** The authority decision compares the actor with the
 * account owner before it consults any grant, so both must come from one snapshot. A rail that
 * resolved the owner through a separate lookup could decide the OWNER short-circuit against a
 * different owner than the one its own write-time proof compares later — two reads, two answers,
 * and nothing observes the window between them. It also removes a round trip from the money path,
 * which already calls this service more than once per delegated payment.
 *
 * **Why the facts come from here at all.** ADR-0232 D3 refuses a call to delegation-service on the
 * money path. It does not refuse a call to the account's own service: a rail already calls
 * account-service there (`AccountServiceClient` in domestic-payment), and this service is the only
 * holder of both the grant projection and the account's true owner. The alternative — six rails
 * keeping six projections of one stream — is the shape this repo has been burned by, where two
 * copies of a rule drift and the money-path copy is the stale one.
 *
 * **What is deliberately NOT here.** The legacy `account_authorizations` arm that
 * `authorizeDelegatedPayment` still ORs in. These are grants only, so a party permitted solely by
 * a legacy row is absent, and a rail deciding from these facts would refuse them. That divergence
 * is already measured by the dual-run disagreement counter — it is a known, instrumented gap
 * rather than something a later refusal would reveal.
 *
 * **Role/action gate.** `account.read` and the same role set as
 * [DelegatedPaymentAuthorizationResource], reusing an existing OPA action rather than minting one,
 * so no `rest.rego` change and no bundle restamp rides along. `partyId` is a query parameter and
 * not `X-Customer-Party-Id`: that header is the fleet's ownership guard and would 404 the very
 * delegate this endpoint answers about.
 */
@Path("/api/v1/accounts/{accountId}/delegation/authority-facts")
@Produces(MediaType.APPLICATION_JSON)
class DelegationAuthorityFactsResource(private val authorizationUseCase: AuthorizationUseCase) {

    /**
     * Every ACTIVE ACCOUNT-scoped grant held by [partyId] over [accountId] whose grantor owns it.
     *
     * An unknown account answers `200` with an empty list, never `404`: a distinguishable
     * not-found would make this an existence oracle for other people's accounts. An empty list is
     * therefore "no authority here", which is also the honest answer for an account that does not
     * exist.
     */
    @GET
    @RolesAllowed(Roles.API, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "account.read", resource = "#accountId")
    @Operation(summary = "Active delegation grants held by a party over the account")
    suspend fun facts(
        @PathParam("accountId") accountId: UUID,
        @QueryParam("partyId") partyId: UUID?,
    ): DelegationAuthorityFactsResponse {
        // #3104 — a `suspend` handler emits no Kotlin null intrinsic at the JAX-RS boundary, so an
        // absent parameter reaches the body as null. libs-runtime maps this guard to 400; without
        // it the request would proceed with a null the signature promises cannot exist.
        requireNotNull(partyId) { "query parameter 'partyId' is required" }
        val facts = authorizationUseCase.delegationFacts(accountId, partyId)
        return DelegationAuthorityFactsResponse(
            accountId = accountId,
            partyId = partyId,
            // Travels with the grants so the caller decides the OWNER case and the delegated case
            // from one snapshot. Null for an unknown account, where `grants` is empty too.
            ownerPartyId = facts.ownerPartyId,
            grants = facts.grants.map {
                DelegationAuthorityGrant(
                    grantId = it.grantId,
                    grantorPartyId = it.grantorPartyId,
                    capabilities = it.capabilities.sorted(),
                    validFrom = it.validFrom.toString(),
                    validTo = it.validTo?.toString(),
                )
            },
        )
    }
}

data class DelegationAuthorityFactsResponse(
    val accountId: UUID,
    val partyId: UUID,
    val ownerPartyId: UUID?,
    val grants: List<DelegationAuthorityGrant>,
)

/**
 * `active` is not on the wire: every grant returned is active, and a field that is always `true`
 * is an invitation to ship a path where it is not. The rail's decoder sets it.
 */
data class DelegationAuthorityGrant(
    val grantId: UUID,
    val grantorPartyId: UUID,
    val capabilities: List<String>,
    val validFrom: String,
    val validTo: String?,
)
