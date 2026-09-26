// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.ForbiddenException
import org.eclipse.microprofile.jwt.JsonWebToken
import java.util.UUID

/**
 * The party a sibling resource's downstream calls are scoped to — the same three steps, in the same
 * order, as `CustomerEdgeResource.customer()`, so a route added outside that class cannot quietly
 * disagree with it about who the caller is:
 *
 *  1. the `party_id` claim (falling back to `sub`) from the verified customer token — never a value
 *     the client supplies in a path, query or body;
 *  2. follow a merge to the surviving party ([PartyMergeResolver], fail-open);
 *  3. honour `X-Acting-For` only through [ActingForResolver] (fail-closed 403).
 */
@ApplicationScoped
class CustomerPartyResolver(private val merges: PartyMergeResolver, private val actingFor: ActingForResolver) {
    @Inject
    lateinit var jwt: JsonWebToken

    fun resolve(actingForHeader: String?): UUID {
        val claimed = CustomerEdgeResource.resolvePartyIdClaim(
            partyIdClaim = jwt.getClaim<String>("party_id"),
            sub = jwt.subject,
        ) ?: throw ForbiddenException("Missing party_id/sub claim in customer token")
        val human = runCatching { UUID.fromString(claimed) }
            .getOrElse { throw ForbiddenException("party_id claim is not a valid party UUID") }
        return actingFor.resolve(merges.resolve(human), actingForHeader)
    }
}
