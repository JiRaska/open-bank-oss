// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.rest

import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.ForbiddenException

/** Staff roles the card reads admitted before #10486; a caller holding one needs no identity check. */
private val CARD_READ_STAFF_ROLES = setOf("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN")

/**
 * #10486 batch 6: machine principals that read ONE card by id (`GET /api/v1/cards/{id}`, `card.read`).
 * Mirrors `service-delegation-card-read` in card_issuance_rest_ext.rego; `CardReadCallerGuardTest`
 * pins that the two agree.
 */
internal val CARD_READ_CALLERS = setOf(
    // delegation-service: resource-ownership check before a card-scoped mandate (CardIssuanceRestClient).
    "service-account-openbank-delegation",
)

/**
 * #10486 batch 6: machine principals that list a party's cards (`GET /api/v1/cards/party/{partyId}`,
 * `card.list`). Mirrors `service-party-card-list` in card_issuance_rest_ext.rego.
 */
internal val CARD_PARTY_LIST_CALLERS = setOf(
    // party-service: GDPR Art. 15 aggregation (CardServiceRestClient).
    "service-account-openbank-party",
)

/**
 * A caller that reached a card read through ROLE_API alone must be named in [allowed]. The enforcing
 * half while card-issuance runs OPA advisory (`AUTHZ_ENFORCE=false`): without it ROLE_API, held by every
 * service account in the realm, would open cardholder data to all of them.
 */
internal fun requireNamedCardReader(identity: SecurityIdentity, allowed: Set<String>) {
    if (CARD_READ_STAFF_ROLES.any(identity::hasRole)) return
    if (identity.principal?.name !in allowed) {
        throw ForbiddenException("caller is not a named card reader")
    }
}
