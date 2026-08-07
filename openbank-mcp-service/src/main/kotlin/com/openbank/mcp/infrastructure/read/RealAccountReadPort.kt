// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.read

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.mcp.application.port.out.AccountReadPort
import com.openbank.mcp.application.port.out.ConsentContext
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.rest.client.inject.RestClient

/**
 * The real read adapter behind [AccountReadPort] (ADR-0195 step 2). Every method LIVE-validates the
 * presented consent at consent-service `POST /consents/{id}/validate` before touching downstream
 * data — `grantedAccounts` is read from THAT response, never from the caller's token, so revoke /
 * expire (ADR-0126 sweeper + outbox) and grantee-match are honoured on every call, not just at
 * token-issue time.
 *
 * MCP tool arguments name an account by its **IBAN** (matching consent-service's own
 * `Consent.accountIbans` grant shape — a PSD2 AISP consent grants IBANs, not internal account
 * UUIDs). Each method resolves the IBAN to the account-service UUID via
 * [AccountServiceClient.getAccountByIban] before calling balance-service / transaction-service,
 * which are keyed by that UUID.
 *
 * The default `AccountReadPort` bean as of ADR-0195 step 4: `StubAccountReadPort` is retired and
 * the `McpEndpoint` placeholder identity is removed in the same change (#2206's ordering — the CI
 * guard `check-mcp-stub-ports-vs-caller-auth.sh`, #2230, enforced this could not happen apart).
 * The M2M OIDC client + downstream URLs it needs landed in step 3 (#2278); the OpenBao secret value
 * itself is a manual operator step (`es-mcp-service.yaml`'s own header comment) — until it is
 * seeded, `consent.validate` and the downstream reads 401, which surfaces as a denied tool call,
 * never as an unauthenticated success.
 */
@ApplicationScoped
class RealAccountReadPort(
    @RestClient private val consent: ConsentValidateClient,
    @RestClient private val accounts: AccountServiceClient,
    @RestClient private val balances: BalanceServiceClient,
    @RestClient private val transactions: TransactionServiceClient,
    private val mapper: ObjectMapper,
) : AccountReadPort {

    // Built from the injected client rather than CDI-injected itself: this keeps the constructor
    // shape the plain-unit RealAccountReadPortTest already constructs positionally (consent,
    // accounts, balances, transactions, mapper) — adding a 6th constructor parameter here would
    // break every one of those call sites for a refactor that changes no behaviour.
    private val gate = ConsentGate(consent)

    override fun listAccounts(consentContext: ConsentContext): JsonNode {
        val validated = gate.validate(consentContext, ConsentScopes.ACCOUNTS_READ, accountIban = null)
        val ibans = validated.grantedAccounts.orEmpty()
        val result = mapper.createArrayNode()
        ibans.forEach { iban -> result.add(accounts.getAccountByIban(iban)) }
        return result
    }

    override fun getBalance(consentContext: ConsentContext, accountId: String): JsonNode {
        gate.validate(consentContext, ConsentScopes.BALANCES_READ, accountIban = accountId)
        val internalId = resolveAccountId(accountId)
        return balances.getBalances(internalId)
    }

    override fun listTransactions(consentContext: ConsentContext, accountId: String, limit: Int): JsonNode {
        gate.validate(consentContext, ConsentScopes.TRANSACTIONS_READ, accountIban = accountId)
        val internalId = resolveAccountId(accountId)
        return transactions.listTransactions(internalId, limit, cursor = null)
    }

    override fun listConsents(consentContext: ConsentContext): JsonNode {
        // consent-service exposes no "list all consents for this grantee" endpoint today (verified:
        // ConsentResource has getById(#id) only, no query-by-grantee route) — the presented consent
        // IS the one consent this call can honestly report on. Validating it also proves it is
        // still live (not revoked/expired), which a bare GET-by-id would not.
        val validated = gate.validate(consentContext, ConsentScopes.ACCOUNTS_READ, accountIban = null)
        return mapper.createArrayNode().add(mapper.valueToTree(validated))
    }

    private fun resolveAccountId(iban: String): String = accounts.getAccountByIban(iban).path("id").asText()
}
