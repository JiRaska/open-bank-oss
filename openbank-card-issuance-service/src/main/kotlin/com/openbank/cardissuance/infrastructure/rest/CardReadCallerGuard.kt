// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.rest

import com.openbank.libs.authz.ServiceAccountIdentity
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.ForbiddenException

/** Staff roles the card reads admitted before #10486, for verified human sessions only. */
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
 * The ONLY principal that may read a virtual card's synthetic PAN/CVV (`GET /api/v1/cards/{id}/secure-details`,
 * `card.details.read`): customer-edge, which reaches it from `POST /customer/v1/cards/{id}/details` only after
 * its own holder-only check and an SCA challenge bound to that card. Mirrors `service-edge-card-details-read`
 * in card_issuance_rest_ext.rego; `CardReadCallerGuardTest` pins that the two agree.
 *
 * Deliberately no staff exemption: no back-office screen renders a PAN (admin-ui keeps the console out of
 * PCI scope), so a staff role — let alone the read-only ROLE_VIEWER — is never a reason to return one.
 */
internal val CARD_DETAILS_READ_CALLERS = setOf(
    // customer-edge: holder-only + SCA-gated reveal (CustomerEdgeResource.revealCardDetails).
    "service-account-openbank-edge",
)

/**
 * Refuses every caller of the PAN/CVV read except a verified [CARD_DETAILS_READ_CALLERS] service account.
 * The enforcing half while card-issuance runs OPA advisory (`AUTHZ_ENFORCE=false`).
 */
internal fun requireCardDetailsReader(identity: SecurityIdentity) {
    if (!ServiceAccountIdentity.isOneOf(identity, CARD_DETAILS_READ_CALLERS)) {
        throw ForbiddenException("caller may not read card secure details")
    }
}

/**
 * A caller that reached a card read through ROLE_API alone must be named in [allowed]. The enforcing
 * half while card-issuance runs OPA advisory (`AUTHZ_ENFORCE=false`): without it ROLE_API, held by every
 * service account in the realm, would open cardholder data to all of them.
 */
internal fun requireNamedCardReader(identity: SecurityIdentity, allowed: Set<String>) {
    if (CARD_READ_STAFF_ROLES.any(identity::hasRole) && ServiceAccountIdentity.isHumanStaff(identity)) return
    if (!ServiceAccountIdentity.isOneOf(identity, allowed)) {
        throw ForbiddenException("caller is not a named card reader")
    }
}
