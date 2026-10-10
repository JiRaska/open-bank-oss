// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.authz

import com.openbank.libs.security.Roles
import com.openbank.pension.application.port.`in`.Caller
import com.openbank.pension.application.port.`in`.ContractVisibility
import com.openbank.pension.domain.model.PensionContract
import io.quarkus.security.identity.SecurityIdentity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.ForbiddenException
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.UUID

/**
 * Resolves WHO is acting on a pension contract, for every REST route in this service. Use this,
 * never a hand-rolled header read: the ownership rule must exist exactly once.
 *
 * ## Where `X-Customer-Party-Id` is trusted from
 *
 * The header is NOT a customer claim. It is stamped by customer-edge AFTER edge has verified the
 * customer's own token and resolved that token's subject to a party; edge then calls this service
 * as its own service-account. So the header is trusted ONLY when the authenticated principal is a
 * configured relay (`openbank.pension.trusted-party-relays`, default the edge service-account) —
 * the binding is "the principal that verified the customer's JWT vouches for this party". A header
 * from any other principal is refused with 403: without that binding, any `ROLE_API` caller could
 * name any party and the ownership check below would be theatre. OPA (`pension_rest_ext.rego`)
 * independently admits participant actions only from the same edge principal.
 *
 * Without the header, only HUMAN staff (operator, admin, compliance) are admitted, as a reader — a
 * service account holding a staff role is refused (403).
 */
@ApplicationScoped
class ContractAccessGuard {

    // Request-scoped behind a client proxy, so it is the CURRENT request's identity.
    @Inject
    lateinit var identity: SecurityIdentity

    @ConfigProperty(name = "openbank.pension.trusted-party-relays", defaultValue = DEFAULT_RELAY)
    lateinit var trustedRelays: List<String>

    /** A caller allowed to READ: the vouched-for participant, or staff without a header. */
    fun readerFor(partyHeader: String?): Caller {
        if (partyHeader != null) return Caller.customer(vouchedParty(partyHeader))
        require(STAFF_ROLES.any(identity::hasRole)) { "header '$PARTY_HEADER' is required" }
        // A staff ROLE is not staff: the shared backend client holds ROLE_OPERATOR in some realms
        // (#3765/#3734), and Caller.STAFF reads every participant's contract. Only a human principal
        // is staff; a machine reads a participant's data solely as the vouching relay, above.
        if (isServiceAccount()) throw ForbiddenException("a service account is not staff")
        return Caller.STAFF
    }

    /** A caller allowed to CHANGE a contract: always a vouched-for participant, never staff. */
    fun actingParticipant(partyHeader: String?): Caller {
        val header = requireNotNull(partyHeader) { "header '$PARTY_HEADER' is required to change a contract" }
        return Caller.customer(vouchedParty(header))
    }

    /** The ownership rule: someone else's contract is NOT FOUND, never forbidden. */
    fun requireVisible(caller: Caller, contract: PensionContract): PensionContract =
        ContractVisibility.requireVisible(caller, contract)

    /**
     * The acting STAFF member's principal name (#12383), for maker/checker records such as the
     * annuity-partner registry's four-eyes activation. Never a party header, never a service account.
     */
    fun staffActor(partyHeader: String?): String {
        require(partyHeader == null) { "staff work is not done on behalf of a participant" }
        require(STAFF_ROLES.any(identity::hasRole)) { "this is staff work" }
        val name = identity.principal?.name
        if (name.isNullOrBlank() || isServiceAccount()) {
            throw ForbiddenException("staff work needs a named human principal")
        }
        return name
    }

    // Pension-local until the libs ServiceAccountIdentity helper (#12463) lands; Keycloak names every
    // client_credentials principal `service-account-<clientId>`.
    private fun isServiceAccount(): Boolean = identity.principal?.name?.startsWith(SERVICE_ACCOUNT_PREFIX) ?: false

    private fun vouchedParty(header: String): UUID {
        val principal = identity.principal?.name
        if (principal == null || principal !in trustedRelays) {
            throw ForbiddenException("'$PARTY_HEADER' is accepted only from a trusted relay")
        }
        return UUID.fromString(header)
    }

    companion object {
        const val PARTY_HEADER = "X-Customer-Party-Id"
        const val DEFAULT_RELAY = "service-account-openbank-edge"
        const val SERVICE_ACCOUNT_PREFIX = "service-account-"
        val STAFF_ROLES = listOf(Roles.OPERATOR, Roles.ADMIN, Roles.COMPLIANCE)
    }
}
