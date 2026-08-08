// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.read

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.mcp.application.port.out.ConsentContext
import jakarta.enterprise.inject.Vetoed
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The real read adapter (ADR-0195 step 2): every read must live-validate the presented consent and
 * enforce the [ConsentValidationResponse.grantedAccounts] intersection — a tool must never reach an
 * account the consent did not grant, and a denied/expired/revoked consent must never leak a partial
 * result. Plain-unit — fake HTTP-shaped collaborators, no Quarkus context.
 */
class RealAccountReadPortTest {

    private val mapper = jacksonObjectMapper()
    private val consentId = UUID.randomUUID()
    private val ctx =
        ConsentContext(agentId = "agent:mcp-tpp-42", consentId = consentId.toString(), grantedAccounts = emptyList())

    private fun accountJson(id: String, iban: String): JsonNode =
        mapper.createObjectNode().put("id", id).put("iban", iban)

    @Test
    fun `listAccounts returns only the granted IBANs, resolved to account records`() {
        val consent = FakeConsentValidateClient(valid = true, grantedAccounts = listOf("CZ01", "CZ02"))
        val accounts = FakeAccountServiceClient(
            mapOf(
                "CZ01" to accountJson("a1", "CZ01"),
                "CZ02" to accountJson("a2", "CZ02"),
            ),
        )
        val port =
            RealAccountReadPort(consent, accounts, FakeBalanceServiceClient(), FakeTransactionServiceClient(), mapper)

        val result = port.listAccounts(ctx)

        assertThat(result.map { it.path("id").asText() }).containsExactlyInAnyOrder("a1", "a2")
        assertThat(consent.lastRequest?.requiredScope).isEqualTo(ConsentScopes.ACCOUNTS_READ)
    }

    @Test
    fun `getBalance resolves the IBAN and returns the balance`() {
        val consent = FakeConsentValidateClient(valid = true, grantedAccounts = listOf("CZ01"))
        val accounts = FakeAccountServiceClient(mapOf("CZ01" to accountJson("a1", "CZ01")))
        val balances = FakeBalanceServiceClient(
            byAccountId = mapOf(
                "a1" to mapper.createObjectNode().put("available", "100.00"),
            ),
        )
        val port = RealAccountReadPort(consent, accounts, balances, FakeTransactionServiceClient(), mapper)

        val result = port.getBalance(ctx, "CZ01")

        assertThat(result.path("available").asText()).isEqualTo("100.00")
        assertThat(consent.lastRequest?.requiredScope).isEqualTo(ConsentScopes.BALANCES_READ)
        assertThat(consent.lastRequest?.accountIban).isEqualTo("CZ01")
    }

    @Test
    fun `getBalance denies an account outside the granted scope`() {
        val consent = FakeConsentValidateClient(valid = true, grantedAccounts = listOf("CZ01"))
        val port = RealAccountReadPort(
            consent,
            FakeAccountServiceClient(emptyMap()),
            FakeBalanceServiceClient(),
            FakeTransactionServiceClient(),
            mapper,
        )

        assertThatThrownBy { port.getBalance(ctx, "CZ99-not-granted") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("not within the granted consent scope")
    }

    @Test
    fun `getBalance denies when the consent itself is invalid (revoked, expired, or wrong grantee)`() {
        val consent = FakeConsentValidateClient(valid = false, reason = "consent revoked")
        val port = RealAccountReadPort(
            consent,
            FakeAccountServiceClient(emptyMap()),
            FakeBalanceServiceClient(),
            FakeTransactionServiceClient(),
            mapper,
        )

        assertThatThrownBy { port.getBalance(ctx, "CZ01") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("consent revoked")
    }

    @Test
    fun `a null grantedAccounts (all of the party's accounts) allows any account`() {
        val consent = FakeConsentValidateClient(valid = true, grantedAccounts = null)
        val accounts = FakeAccountServiceClient(mapOf("CZ07" to accountJson("a7", "CZ07")))
        val transactions = FakeTransactionServiceClient(byAccountId = mapOf("a7" to mapper.createArrayNode()))
        val port = RealAccountReadPort(consent, accounts, FakeBalanceServiceClient(), transactions, mapper)

        assertThat(port.listTransactions(ctx, "CZ07", limit = 10)).isNotNull
    }

    @Test
    fun `a malformed consent id fails closed`() {
        val port = RealAccountReadPort(
            FakeConsentValidateClient(valid = true),
            FakeAccountServiceClient(emptyMap()),
            FakeBalanceServiceClient(),
            FakeTransactionServiceClient(),
            mapper,
        )
        val badCtx =
            ConsentContext(agentId = "agent:mcp-tpp-42", consentId = "not-a-uuid", grantedAccounts = emptyList())

        assertThatThrownBy { port.listAccounts(badCtx) }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `listConsents reports the presented consent's own live validation`() {
        val consent = FakeConsentValidateClient(valid = true, grantedAccounts = listOf("CZ01"), frequencyPerDay = 4)
        val port = RealAccountReadPort(
            consent,
            FakeAccountServiceClient(emptyMap()),
            FakeBalanceServiceClient(),
            FakeTransactionServiceClient(),
            mapper,
        )

        val result = port.listConsents(ctx)

        assertThat(result).hasSize(1)
        assertThat(result[0].path("valid").asBoolean()).isTrue()
        assertThat(result[0].path("frequencyPerDay").asInt()).isEqualTo(4)
    }
}

// @Vetoed: Quarkus's implicit bean discovery would otherwise turn these test doubles into CDI
// bean candidates for the interfaces they implement (RealAccountReadPortTest is a plain-unit test,
// no Quarkus context — but @QuarkusTest classes elsewhere in this module share one Arc validation).
// NOT `private`, and deliberately the ONE implementation of each interface in this test source
// set: [AccountServiceClient] / [ConsentValidateClient] carry JAX-RS annotations for the MP Rest
// Client, and RESTEasy Reactive scans the whole test classpath for classes implementing an
// @Path-annotated interface regardless of CDI/visibility — a SECOND fake implementing the same
// interface elsewhere in this module (even in a different file) makes Quarkus see two conflicting
// declarations of the same route and fail every @QuarkusTest at boot
// ("GET /api/v1/accounts is declared by: ... and ..."). RealStatementReadPortTest and
// RealPaymentConfirmationReadPortTest reuse these two rather than declaring their own.
@Vetoed
class FakeConsentValidateClient(
    private val valid: Boolean,
    private val grantedAccounts: List<String>? = null,
    private val reason: String? = null,
    private val frequencyPerDay: Int? = null,
) : ConsentValidateClient {
    var lastRequest: ValidateConsentRequest? = null

    override fun validate(id: UUID, request: ValidateConsentRequest): ConsentValidationResponse {
        lastRequest = request
        return ConsentValidationResponse(
            valid = valid,
            reason = reason,
            code = if (valid) null else "DENIED",
            scopes = setOf(request.requiredScope),
            grantedAccounts = grantedAccounts,
            frequencyPerDay = frequencyPerDay,
        )
    }
}

@Vetoed
class FakeAccountServiceClient(
    private val byIban: Map<String, JsonNode> = emptyMap(),
    private val byId: Map<UUID, JsonNode> = emptyMap(),
) : AccountServiceClient {
    override fun getAccountByIban(iban: String): JsonNode =
        byIban[iban] ?: throw NoSuchElementException("no such account: $iban")
    override fun getAccountById(accountId: UUID): JsonNode =
        byId[accountId] ?: throw NoSuchElementException("no such account: $accountId")
}

@Vetoed
private class FakeBalanceServiceClient(private val byAccountId: Map<String, JsonNode> = emptyMap()) :
    BalanceServiceClient {
    override fun getBalances(accountId: String): JsonNode =
        byAccountId[accountId] ?: throw NoSuchElementException("no balance for: $accountId")
}

@Vetoed
private class FakeTransactionServiceClient(private val byAccountId: Map<String, JsonNode> = emptyMap()) :
    TransactionServiceClient {
    override fun listTransactions(accountId: String, limit: Int, cursor: String?): JsonNode =
        byAccountId[accountId] ?: throw NoSuchElementException("no transactions for: $accountId")
}
