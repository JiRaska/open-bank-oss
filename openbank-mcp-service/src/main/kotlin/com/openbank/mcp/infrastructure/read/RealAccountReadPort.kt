// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.read

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.mcp.application.port.out.AccountReadPort
import com.openbank.mcp.application.port.out.ConsentContext
import jakarta.enterprise.inject.Vetoed
import java.util.UUID

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
 * NOT WIRED as the default port yet: mcp-service has no provisioned M2M OIDC client for
 * `consent-service`/`account-service`/`balance-service`/`transaction-service` and no `rest.rego`
 * grant for `consent.validate` — that infra + the switch away from `StubAccountReadPort` land
 * together (ADR-0195 next step). The `check-mcp-stub-ports-vs-caller-auth.sh` CI guard (#2230)
 * refuses that switch until the phase-1 placeholder identity is removed from `McpEndpoint` first.
 *
 * [Vetoed]: NOT a CDI bean in this PR — Quarkus's implicit bean discovery would otherwise make it a
 * `@Default`-qualified `AccountReadPort` candidate ambiguous with `StubAccountReadPort`. The wiring
 * step (which removes the stub / decides the switch mechanism) adds the scope annotation back.
 */
@Vetoed
class RealAccountReadPort(
    private val consent: ConsentValidateClient,
    private val accounts: AccountServiceClient,
    private val balances: BalanceServiceClient,
    private val transactions: TransactionServiceClient,
    private val mapper: ObjectMapper,
) : AccountReadPort {

    override fun listAccounts(consentContext: ConsentContext): JsonNode {
        val validated = validate(consentContext, ConsentScopes.ACCOUNTS_READ, accountIban = null)
        val ibans = validated.grantedAccounts.orEmpty()
        val result = mapper.createArrayNode()
        ibans.forEach { iban -> result.add(accounts.getAccountByIban(iban)) }
        return result
    }

    override fun getBalance(consentContext: ConsentContext, accountId: String): JsonNode {
        validate(consentContext, ConsentScopes.BALANCES_READ, accountIban = accountId)
        val internalId = resolveAccountId(accountId)
        return balances.getBalances(internalId)
    }

    override fun listTransactions(consentContext: ConsentContext, accountId: String, limit: Int): JsonNode {
        validate(consentContext, ConsentScopes.TRANSACTIONS_READ, accountIban = accountId)
        val internalId = resolveAccountId(accountId)
        return transactions.listTransactions(internalId, limit, cursor = null)
    }

    override fun listConsents(consentContext: ConsentContext): JsonNode {
        // consent-service exposes no "list all consents for this grantee" endpoint today (verified:
        // ConsentResource has getById(#id) only, no query-by-grantee route) — the presented consent
        // IS the one consent this call can honestly report on. Validating it also proves it is
        // still live (not revoked/expired), which a bare GET-by-id would not.
        val validated = validate(consentContext, ConsentScopes.ACCOUNTS_READ, accountIban = null)
        return mapper.createArrayNode().add(mapper.valueToTree(validated))
    }

    /**
     * Live-validates [consentContext]'s consent for [scope] (and, when given, that [accountIban] is
     * within its granted accounts). Throws (fails closed — never returns a partially-checked result)
     * when the consent is invalid, revoked, expired, or does not cover the requested account.
     */
    private fun validate(
        consentContext: ConsentContext,
        scope: String,
        accountIban: String?,
    ): ConsentValidationResponse {
        val consentId = runCatching { UUID.fromString(consentContext.consentId) }.getOrElse {
            error("consent id '${consentContext.consentId}' is not a valid PSD2 consent id")
        }
        val response = consent.validate(
            consentId,
            ValidateConsentRequest(consentContext.agentId, scope, accountIban),
        )
        if (!response.valid) {
            error("consent denied: ${response.reason ?: response.code ?: "not valid"}")
        }
        val granted = response.grantedAccounts
        if (accountIban != null && granted != null && accountIban !in granted) {
            error("account '$accountIban' is not within the granted consent scope")
        }
        return response
    }

    private fun resolveAccountId(iban: String): String = accounts.getAccountByIban(iban).path("id").asText()
}
