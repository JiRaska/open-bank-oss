// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.security

import com.openbank.libs.authz.ServiceAccountIdentity
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.ForbiddenException

/**
 * Who owns a monitored subject: the service whose own service account made the call (ADR-0376).
 *
 * The owner is never taken from the request. It is the client the token was issued to, as
 * [ServiceAccountIdentity.verifiedClientId] establishes it (a verified JWT whose `azp` is bound
 * to that client's service-account username), so a caller can name no owner but itself.
 *
 * Refused with 403, and nothing is read or written:
 *  - a person's token, whatever roles it carries: a registration belongs to a service;
 *  - a principal that is not a verified service-account token;
 *  - the fleet's shared client, because every service holding it would be the same owner.
 */
object MonitoringCallerIdentity {

    /** The client several services may still authenticate as; it identifies no single owner. */
    const val SHARED_FLEET_CLIENT = "openbank-services"

    fun ownerOf(identity: SecurityIdentity?): String {
        val clientId = ServiceAccountIdentity.verifiedClientId(identity)
        if (clientId == null || clientId == SHARED_FLEET_CLIENT) {
            throw ForbiddenException("ongoing monitoring is registered by a service under its own service account")
        }
        return clientId
    }
}
