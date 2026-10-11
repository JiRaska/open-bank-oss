// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.authz

import io.quarkus.security.identity.SecurityIdentity
import org.eclipse.microprofile.jwt.JsonWebToken
import java.security.Principal

/**
 * The ONE test for "this request comes from service X's own Keycloak service-account" (#12448).
 *
 * Never compare `SecurityIdentity.principal.name` with a `service-account-<client>` string: Quarkus
 * derives that name from `upn`/`preferred_username`, a USERNAME claim, so the comparison trusts a
 * username rather than the client the token was issued to. A token is a service-account token of
 * client `C` only when ALL hold:
 *  - the principal is a verified JWT (Quarkus OIDC has checked signature and issuer);
 *  - `sub` is present;
 *  - `azp` — the client the token was issued TO — is `C`;
 *  - `preferred_username` is `service-account-C`, the name Keycloak gives a client_credentials
 *    grant. A human logging in through client `C` carries their own username and fails this.
 *
 * Callers keep their existing allow-lists of principal NAMES (`service-account-<client>`); the name
 * is only ever compared with the one DERIVED from the verified `azp`, never with the token's own
 * name claim alone. ServiceAccountIdentityTest exercises these identity checks.
 */
object ServiceAccountIdentity {

    /** Keycloak's service-account username convention: `service-account-<clientId>`. */
    const val PRINCIPAL_PREFIX = "service-account-"

    private const val CLAIM_AZP = "azp"
    private const val CLAIM_PREFERRED_USERNAME = "preferred_username"
    private const val CLAIM_CLIENT_ID = "client_id"
    private const val CLAIM_CLIENT_ID_LEGACY = "clientId"

    /** Interactive clients in the realm templates; none issues client-credentials tokens. */
    private val HUMAN_CLIENT_IDS = setOf("openbank-admin-ui", "openbank-ops-cli", "admin-cli")

    /** A staff role is usable only with an interactive, verified user JWT, never an M2M JWT. */
    fun isHumanStaff(identity: SecurityIdentity?): Boolean {
        val jwt = identity?.principal as? JsonWebToken ?: return false
        val clientId = jwt.claimString(CLAIM_AZP) ?: return false
        val username = jwt.claimString(CLAIM_PREFERRED_USERNAME) ?: return false
        return clientId in HUMAN_CLIENT_IDS &&
            !jwt.subject.isNullOrBlank() &&
            username.isNotBlank() &&
            !username.startsWith(PRINCIPAL_PREFIX) &&
            jwt.name == username
    }

    /** The client id whose OWN service-account issued this token, or `null` if it is not one. */
    fun verifiedClientId(identity: SecurityIdentity?): String? = verifiedClientId(identity?.principal)

    /** JAX-RS filters can verify a JWT principal without injecting [SecurityIdentity]. */
    fun verifiedClientId(principal: Principal?): String? {
        val jwt = principal as? JsonWebToken ?: return null
        if (jwt.subject.isNullOrBlank()) return null
        val clientId = jwt.claimString(CLAIM_AZP)?.takeIf { it.isNotBlank() } ?: return null
        return clientId.takeIf { jwt.claimString(CLAIM_PREFERRED_USERNAME) == PRINCIPAL_PREFIX + it }
    }

    /**
     * The verified client of a MACHINE token, or `null` when the verified token is not one — the
     * value the PEP hands OPA as `input.principal.service_account`/`client_id`.
     *
     * Broader than [verifiedClientId] on purpose: that one answers "is this exactly client C's own
     * service-account" (for granting a SPECIFIC client), this one answers "is this a machine at
     * all" (for keeping machines OUT), so it must fail towards `machine`. A JWT is a machine token
     * when ANY holds:
     *  - it carries `client_id` (or legacy `clientId`), the session note Keycloak sets only for a
     *    client-credentials grant; a user login never carries it;
     *  - `preferred_username` starts with `service-account-`;
     *  - `preferred_username` is absent or blank: no username means no human to attribute to.
     * None of these reads the principal NAME Quarkus derives (`upn` first), so a renamed user, a
     * client_id-only token or name-mapper drift cannot make a machine look human.
     * A non-JWT identity returns `null`: only OIDC bearer tokens authenticate in deployment.
     * Returns `""` for a machine token that names no client.
     */
    fun machineClientId(identity: SecurityIdentity?): String? = machineClientId(identity?.principal)

    /** JAX-RS form of [machineClientId]. */
    fun machineClientId(principal: Principal?): String? {
        val jwt = principal as? JsonWebToken ?: return null
        // A claim that cannot be read is not evidence of a human: fail towards machine.
        return runCatching {
            val noted = (jwt.claimString(CLAIM_CLIENT_ID) ?: jwt.claimString(CLAIM_CLIENT_ID_LEGACY))
                ?.takeIf { it.isNotBlank() }
            val username = jwt.claimString(CLAIM_PREFERRED_USERNAME)
            val machine = noted != null || username.isNullOrBlank() || username.startsWith(PRINCIPAL_PREFIX)
            if (!machine) null else noted ?: jwt.claimString(CLAIM_AZP)?.takeIf { it.isNotBlank() } ?: ""
        }.getOrDefault("")
    }

    /** The verified service-account principal name (`service-account-<azp>`), or `null`. */
    fun verifiedPrincipalName(identity: SecurityIdentity?): String? =
        verifiedClientId(identity)?.let { PRINCIPAL_PREFIX + it }

    /** True iff the caller is the service-account of a client whose principal name is in [allowedPrincipalNames]. */
    fun isOneOf(identity: SecurityIdentity?, allowedPrincipalNames: Collection<String>): Boolean =
        verifiedPrincipalName(identity)?.let { it in allowedPrincipalNames } ?: false

    /** Single-name form of [isOneOf]; a blank [allowedPrincipalName] admits nobody. */
    fun isPrincipal(identity: SecurityIdentity?, allowedPrincipalName: String?): Boolean =
        !allowedPrincipalName.isNullOrBlank() && isOneOf(identity, setOf(allowedPrincipalName))

    /** True iff the caller is the service-account of a client whose CLIENT ID is in [allowedClientIds]. */
    fun isClient(identity: SecurityIdentity?, allowedClientIds: Collection<String>): Boolean =
        verifiedClientId(identity)?.let { it in allowedClientIds } ?: false

    private fun JsonWebToken.claimString(name: String): String? = getClaim<Any?>(name) as? String
}
