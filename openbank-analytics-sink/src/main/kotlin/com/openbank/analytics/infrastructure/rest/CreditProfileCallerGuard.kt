// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.rest

import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.ForbiddenException

/** Staff roles the credit profile admitted before #10486; a caller holding one needs no identity check. */
private val CREDIT_PROFILE_STAFF_ROLES = setOf("ROLE_OPERATOR", "ROLE_AUDITOR", "ROLE_ADMIN")

/**
 * #10486 batch 6: the machine principals that read a party's credit profile, each authenticating as
 * its OWN Keycloak client (ROLE_API only). `CreditProfileCallerGuardTest` pins this set to the
 * callers' own oidc-client configuration, so a caller that stops presenting its identity (or a new
 * one that starts) is a red test rather than a silent 403.
 */
internal val CREDIT_PROFILE_CALLERS = setOf(
    // copilot-service: the AI advisor's financial-health context (CreditProfileReadClient).
    "service-account-openbank-copilot",
    // lending-service: the credit-offer gate (CreditProfileClient).
    "service-account-openbank-lending",
)

/**
 * A caller that reached the credit profile through ROLE_API alone must be one of
 * [CREDIT_PROFILE_CALLERS]. analytics-sink runs no OPA sidecar, so this check IS the authorization
 * for machine callers: without it ROLE_API, held by every service account in the realm, would let any
 * of them read any party's income and outflow figures.
 */
internal fun requireNamedCreditProfileCaller(identity: SecurityIdentity) {
    if (CREDIT_PROFILE_STAFF_ROLES.any(identity::hasRole)) return
    if (identity.principal?.name !in CREDIT_PROFILE_CALLERS) {
        throw ForbiddenException("caller is not a named credit-profile reader")
    }
}
