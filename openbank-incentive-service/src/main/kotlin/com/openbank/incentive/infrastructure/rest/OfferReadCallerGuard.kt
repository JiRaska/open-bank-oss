// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.incentive.infrastructure.rest

import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.ForbiddenException

/** The staff role the offer read admitted before #10486; a caller holding it needs no identity check. */
private const val OFFER_READ_STAFF_ROLE = "ROLE_OPERATOR"

/**
 * #10486 batch 6: the machine principals that read one incentive offer, each authenticating as its
 * OWN Keycloak client (ROLE_API only). `OfferReadCallerGuardTest` pins this set to the callers' own
 * oidc-client configuration.
 */
internal val OFFER_READ_CALLERS = setOf(
    // campaign-service: resolves the offer a campaign grants (IncentiveServiceClient.offer).
    "service-account-openbank-campaign",
)

/**
 * A caller that reached `GET /offers/{id}` through ROLE_API alone must be one of
 * [OFFER_READ_CALLERS]. incentive-service runs no OPA sidecar, so this check IS the authorization for
 * machine callers: without it ROLE_API, held by every service account, would open the offer read to
 * all of them.
 */
internal fun requireNamedOfferReader(identity: SecurityIdentity) {
    if (identity.hasRole(OFFER_READ_STAFF_ROLE)) return
    if (identity.principal?.name !in OFFER_READ_CALLERS) {
        throw ForbiddenException("caller is not a named incentive-offer reader")
    }
}
