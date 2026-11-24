// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.customer

import java.time.Instant
import java.util.UUID

/**
 * The ADR-0383 D3 lookup key: the customer's party (from the verified token), the admitted client
 * (`azp`) and the DPoP key the token is bound to (`cnf.jkt`). Nothing in it is client-supplied
 * beyond what admission has already verified, and the party is part of it, so one customer's grant
 * can never answer for another customer of the same client.
 */
data class GrantKey(val partyId: UUID, val clientId: String, val keyThumbprint: String?, val connectionId: UUID? = null)

/** One ACTIVE `MCP_CLIENT` grant over one account, as delegation-service answered it. */
data class CustomerGrant(
    val grantId: UUID,
    val accountId: UUID,
    val capabilities: Set<String>,
    val validTo: Instant?,
) {
    fun allows(capability: String): Boolean = capability in capabilities
}

/**
 * Source of truth for customer grants (delegation-service). An EMPTY answer means "no active
 * grant"; every failure to get an answer throws [GrantLookupUnavailable] instead, so the two can
 * never be confused into a cached or permissive result.
 */
interface CustomerGrantSource {
    fun resolve(key: GrantKey): List<CustomerGrant>
}

class GrantLookupUnavailable(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Delegation capability names (delegation-service `DelegationCapability`, string form). */
object CustomerCapabilities {
    const val VIEW_DETAILS = "ACCOUNT_VIEW_DETAILS"
    const val READ_BALANCES = "ACCOUNT_READ_BALANCES"
    const val READ_TRANSACTIONS = "ACCOUNT_READ_TRANSACTIONS"

    /** ADR-0383 D6: may PROPOSE a payment from the account; approving stays with the customer's SCA. */
    const val PROPOSE_PAYMENT = "ACCOUNT_PROPOSE_PAYMENT"
}
