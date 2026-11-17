// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.read

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.mcp.application.port.out.ConsentContext
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import jakarta.enterprise.inject.Vetoed
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.UUID

/**
 * The real read adapter (ADR-0195 step 2): every read must live-validate the presented consent and
 * enforce the [ConsentValidationResponse.grantedAccounts] intersection — a tool must never reach an
 * account the consent did not grant, and a denied/expired/revoked consent must never leak a partial
 * result. Plain-unit — fake HTTP-shaped collaborators, no Quarkus context.
 */
private const val A1 = "00000000-0000-0000-0000-0000000000a1"
private const val A2 = "00000000-0000-0000-0000-0000000000a2"
private const val A7 = "00000000-0000-0000-0000-0000000000a7"

class RealAccountReadPortTest {

    private val mapper = jacksonObjectMapper()
    private val consentId = UUID.randomUUID()
    private val ctx =
        ConsentContext(agentId = "agent:mcp-tpp-42", consentId = consentId.toString(), grantedAccounts = emptyList())

    private fun accountJson(id: String, iban: String): JsonNode =
        mapper.createObjectNode().put("id", id).put("accountNumber", iban)

    @Test
    fun `listAccounts returns only the granted IBANs, resolved to account records`() {
        val consent = FakeConsentValidateClient(valid = true, grantedAccounts = listOf("CZ01", "CZ02"))
        val accounts = accountServiceStub(
            mapOf(
                "CZ01" to accountJson(A1, "CZ01"),
                "CZ02" to accountJson(A2, "CZ02"),
            ),
        )
        val port =
            RealAccountReadPort(consent, accounts, FakeBalanceServiceClient(), FakeTransactionServiceClient(), mapper)

        val result = port.listAccounts(ctx)

        assertThat(result.map { it.path("id").asText() }).containsExactlyInAnyOrder(A1, A2)
        assertThat(consent.lastRequest?.requiredScope).isEqualTo(ConsentScopes.ACCOUNTS_READ)
    }

    @Test
    fun `getBalance resolves the IBAN and returns the balance`() {
        val consent = FakeConsentValidateClient(valid = true, grantedAccounts = listOf("CZ01"))
        val accounts = accountServiceStub(mapOf("CZ01" to accountJson(A1, "CZ01")))
        val balances = FakeBalanceServiceClient(
            byAccountId = mapOf(
                A1 to mapper.createObjectNode().put("available", "100.00"),
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
            accountServiceStub(emptyMap()),
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
            accountServiceStub(emptyMap()),
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
        val accounts = accountServiceStub(mapOf("CZ07" to accountJson(A7, "CZ07")))
        val transactions = FakeTransactionServiceClient(byAccountId = mapOf(A7 to mapper.createArrayNode()))
        val port = RealAccountReadPort(consent, accounts, FakeBalanceServiceClient(), transactions, mapper)

        assertThat(port.listTransactions(ctx, "CZ07", limit = 10)).isNotNull
    }

    @Test
    fun `a malformed consent id fails closed`() {
        val port = RealAccountReadPort(
            FakeConsentValidateClient(valid = true),
            accountServiceStub(emptyMap()),
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
            accountServiceStub(emptyMap()),
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

/**
 * A stub of the GENERATED account-service client (#13169), answering as the wire does: a missing account is a
 * real 404 [Response], a found one a 200 whose body the facade reads as a stream. NOT a class implementing
 * [AccountServiceClient]: a second implementation of an @Path-annotated interface is a duplicate-route boot
 * failure for every @QuarkusTest in this module (see the note above).
 */
fun accountServiceStub(
    byIban: Map<String, JsonNode> = emptyMap(),
    byId: Map<UUID, JsonNode> = emptyMap(),
): AccountServiceClient = mockk<AccountServiceClient>().also { client ->
    every { client.getAccountByIban(any()) } answers {
        Uni.createFrom().item(accountResponse(byIban[firstArg<String>()]))
    }
    every { client.getAccount(any()) } answers { Uni.createFrom().item(accountResponse(byId[firstArg<UUID>()])) }
}

fun accountResponse(body: JsonNode?): Response = mockk<Response>(relaxed = true).also {
    every { it.status } returns if (body == null) 404 else 200
    every { it.hasEntity() } returns (body != null)
    every { it.readEntity(InputStream::class.java) } answers { ByteArrayInputStream(body.toString().toByteArray()) }
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
