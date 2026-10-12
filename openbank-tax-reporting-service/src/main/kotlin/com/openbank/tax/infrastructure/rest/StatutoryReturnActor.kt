// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.rest

import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.jwt.JsonWebToken

/**
 * Resolves the person who assembles, approves or submits a statutory return — and refuses a
 * machine. A return's maker/checker/submitter is a regulatory attestation by a member of staff;
 * a Keycloak service-account carries staff roles in some realms and is classified HUMAN by the
 * authz layer, so roles cannot tell the two apart.
 *
 * A token is treated as a service-account token when ANY of these holds — refusing on the union
 * is the safe direction:
 *  - the principal name or `preferred_username` carries Keycloak's `service-account-` prefix;
 *  - `preferred_username` is `service-account-<azp>` (the client_credentials shape, whatever name
 *    Quarkus derived for the principal);
 *  - the token carries Keycloak's `client_id` claim, which only client_credentials grants mint.
 *
 * Kept local until `ServiceAccountIdentity` (#12463) lands in libs-runtime; then this delegates
 * to it.
 */
internal object StatutoryReturnActor {
    private const val PREFIX = "service-account-"

    fun isServiceAccount(identity: SecurityIdentity?): Boolean {
        val principal = identity?.principal ?: return false
        if (principal.name.orEmpty().startsWith(PREFIX)) return true
        val jwt = principal as? JsonWebToken ?: return false
        val username = jwt.claimString("preferred_username")
        val azp = jwt.claimString("azp")
        return username?.startsWith(PREFIX) == true ||
            (azp != null && username == PREFIX + azp) ||
            jwt.claimString("client_id") != null
    }

    /** The acting staff member's subject, or 401 (no subject) / 403 (a machine). */
    fun staffSubject(identity: SecurityIdentity): String {
        if (isServiceAccount(identity)) {
            throw WebApplicationException(
                Response.status(Response.Status.FORBIDDEN)
                    .entity(
                        mapOf(
                            "error" to "A service account may not assemble, approve or submit a statutory return",
                        ),
                    )
                    .type(MediaType.APPLICATION_JSON)
                    .build(),
            )
        }
        val principal = identity.principal
        val subject = (principal as? JsonWebToken)?.subject ?: principal?.name
        if (subject.isNullOrBlank()) {
            throw WebApplicationException("Cannot resolve the acting principal", Response.Status.UNAUTHORIZED)
        }
        return subject
    }

    private fun JsonWebToken.claimString(name: String): String? =
        (getClaim<Any?>(name))?.toString()?.trim('"')?.takeIf { it.isNotBlank() }
}
