// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.rest

import com.openbank.lending.application.usecase.CreditOfferEligibilityService
import com.openbank.lending.domain.model.CreditOfferDecision
import com.openbank.lending.domain.model.OfferSurface
import com.openbank.libs.authz.Authorize
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.asUni
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

/**
 * May the bank say something about credit to this customer, unprompted? (ADR-0269 rules 1 and 2.)
 *
 * ## Why this route exists
 *
 * [CreditOfferEligibilityService] describes itself as the one place any credit offer is cleared —
 * "everything upstream of an offer asks this service and acts on nothing else". That was true of
 * its intent and false of its reach: it had no wire surface at all, so the only caller was
 * in-process ([CustomerQuoteResource], asking the PULL question). Every out-of-process surface that
 * ADR-0269 governs — a campaign step, a pre-approved tile, an agent nudge — had no way to ask.
 *
 * The result was that rule 2, the distress suppression floor, was enforced nowhere on the push
 * side: a customer who had switched credit offers on and then fallen into arrears was still
 * marketed to. This route is the missing half of that sentence, not a new policy.
 *
 * ## Why PUSH is fixed, and not a parameter
 *
 * The surface is not the caller's to choose. PULL exists for a customer who ASKED — it deliberately
 * skips the consent half, because requiring an opt-in before answering a question the customer just
 * asked is a dark pattern in the other direction. Letting a caller name its own surface would make
 * that exemption available to anyone who passed the right string, which is precisely the asymmetry
 * the ADR spends rule 1 constructing. A caller that legitimately serves a customer-initiated
 * request already has [CustomerQuoteResource].
 *
 * ## What a caller must do with a failure
 *
 * Refuse. Not "proceed and log": an unreachable eligibility service means the bank does not know
 * whether it may market credit to this person, and the answer to not knowing is no. This route
 * answers with a decision or an error; it never answers "probably fine".
 */
@Path("/api/v1/lending/credit-offers")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Lending", description = "Customer self-service origination intake")
@RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
class CreditOfferEligibilityResource(private val eligibility: CreditOfferEligibilityService) {

    @GET
    @Path("/eligibility/{partyId}")
    @Authorize(action = "lending.creditOffer.eligibility", resource = "")
    @Operation(summary = "Whether an UNPROMPTED credit offer may be surfaced to this party")
    fun eligibility(@PathParam("partyId") partyId: UUID): Uni<Response> =
        CoroutineScope(Dispatchers.Unconfined).async {
            // 200 for both outcomes, on purpose. "You may not offer" is a successful answer to the
            // question asked, not a client error — and a caller that has to distinguish a refusal
            // from a transport failure by status code will eventually get it wrong in the
            // permissive direction. The reason lives in the body; the absence of a body is the
            // failure.
            when (val decision = eligibility.evaluate(partyId, OfferSurface.PUSH)) {
                is CreditOfferDecision.Allowed -> Response.ok(
                    mapOf(
                        "allowed" to true,
                        "reasonCode" to null,
                        "policyVersion" to decision.policyVersion,
                    ),
                ).build()

                is CreditOfferDecision.Suppressed -> Response.ok(
                    mapOf(
                        // The code is part of the contract, not a log line: a caller has to be able
                        // to count "declined for arrears" separately from "never opted in", and a
                        // conduct review will ask for exactly that split.
                        "allowed" to false,
                        "reasonCode" to decision.code.name,
                        "policyVersion" to decision.policyVersion,
                    ),
                ).build()
            }
        }.asUni()
}
