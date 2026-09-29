// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.rest

import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.ForbiddenException

/** Staff roles `createParty` admitted before #10486; a caller holding one needs no identity check. */
private val PARTY_CREATE_STAFF_ROLES = setOf("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_KYC")

/**
 * #10486 batch 3: the machine principals that create parties, each authenticating as its OWN
 * Keycloak client (ROLE_API only). Mirrors `service-kyb-party-m2m` (for `party.create`) in
 * party_rest_ext.rego; `PartyCreateCallerGuardTest` pins that the two agree.
 */
internal val PARTY_CREATE_CALLERS = setOf("service-account-openbank-kyb")

/**
 * A caller that reached `createParty` through ROLE_API alone must be one of [PARTY_CREATE_CALLERS].
 * The enforcing half while party-service runs OPA advisory: without it, ROLE_API — held by every
 * service account in the realm — would let any of them create a party.
 */
internal fun requireNamedPartyCreateCaller(identity: SecurityIdentity) {
    if (PARTY_CREATE_STAFF_ROLES.any(identity::hasRole)) return
    if (identity.principal?.name !in PARTY_CREATE_CALLERS) {
        throw ForbiddenException("caller is not a named party-create service")
    }
}
