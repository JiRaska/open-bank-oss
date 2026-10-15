// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.rest

import com.openbank.libs.authz.ServiceAccountIdentity
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.ForbiddenException

/** Staff roles `createParty` admitted before #10486, for verified human sessions only. */
private val PARTY_CREATE_STAFF_ROLES = setOf("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_KYC")

/**
 * Machine principals that create parties, each authenticating as its OWN Keycloak client.
 * KYB creates legal-person parties; customer-edge creates individual parties during onboarding.
 * Both `party.create` policy rules in party_rest_ext.rego mirror this list.
 */
internal val PARTY_CREATE_CALLERS = setOf("service-account-openbank-kyb", "service-account-openbank-edge")

/**
 * A caller that reached `createParty` through ROLE_API alone must be one of [PARTY_CREATE_CALLERS].
 * The enforcing half while party-service runs OPA advisory: without it, ROLE_API — held by every
 * service account in the realm — would let any of them create a party.
 */
internal fun requireNamedPartyCreateCaller(identity: SecurityIdentity) {
    if (PARTY_CREATE_STAFF_ROLES.any(identity::hasRole) && ServiceAccountIdentity.isHumanStaff(identity)) return
    if (!ServiceAccountIdentity.isOneOf(identity, PARTY_CREATE_CALLERS)) {
        throw ForbiddenException("caller is not a named party-create service")
    }
}
