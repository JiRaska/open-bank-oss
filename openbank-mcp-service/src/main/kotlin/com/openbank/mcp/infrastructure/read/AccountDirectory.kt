// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.read

import com.openbank.clients.account.AccountClient
import com.openbank.clients.account.AccountLookup
import com.openbank.clients.account.model.AccountResponse
import jakarta.ws.rs.NotFoundException
import java.util.UUID

/**
 * The read ports' one view of account-service (ADR-0319, #13169): the generated client, read through the
 * shared [AccountClient] facade, with the semantics the hand-written client had.
 *
 *  - found -> the typed account;
 *  - 404 -> [NotFoundException], exactly the 404 the previous client raised (the tool call is denied, never
 *    answered with an empty account);
 *  - any other status or a failed call -> thrown unchanged (`AccountHttpException`, `AccountBodyException`, or
 *    the transport failure). Nothing is swallowed here, and a `CancellationException` passes straight through.
 *
 * Reads block: the previous client was a blocking MP Rest Client, so the calling thread is unchanged.
 */
internal class AccountDirectory(accounts: AccountServiceClient) {
    private val client = AccountClient(accounts)

    fun byIban(iban: String): AccountResponse =
        client.getAccountByIban(iban).await().indefinitely().foundOrThrow("IBAN")

    fun byId(accountId: UUID): AccountResponse = client.getAccount(accountId).await().indefinitely().foundOrThrow("id")

    /** The internal account id behind [iban]; an account without an id is a malformed answer, not a result. */
    fun idByIban(iban: String): UUID {
        val account = byIban(iban)
        return checkNotNull(account.id) { "account-service returned an account without an id" }
    }

    private fun AccountLookup.foundOrThrow(by: String): AccountResponse = when (this) {
        is AccountLookup.Found -> account
        AccountLookup.NotFound -> throw NotFoundException("account-service holds no account for this $by")
    }
}
