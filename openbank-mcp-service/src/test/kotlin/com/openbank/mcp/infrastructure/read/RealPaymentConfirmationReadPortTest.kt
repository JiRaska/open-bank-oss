// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.read

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.mcp.application.port.out.ConsentContext
import jakarta.enterprise.inject.Vetoed
import jakarta.ws.rs.NotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [RealPaymentConfirmationReadPort] (issue #4109, ADR-0248): the try-SEPA-then-domestic lookup, the
 * consent-scope check done up front, and the account-intersection check done again once the debtor
 * account is known (see the port's class KDoc for why the order differs from every other adapter on
 * this surface).
 */
class RealPaymentConfirmationReadPortTest {

    private val mapper = jacksonObjectMapper()
    private val consentId = UUID.randomUUID()
    private val ctx =
        ConsentContext(agentId = "agent:mcp-tpp-42", consentId = consentId.toString(), grantedAccounts = emptyList())
    private val paymentId = UUID.randomUUID()

    @Test
    fun `finds a SEPA payment and tags it with its rail`() {
        val consent = FakeConsentValidateClient(valid = true, grantedAccounts = listOf("CZ01"))
        val sepa = FakeSepaPaymentServiceClient(mapOf(paymentId to sepaPaymentJson("CZ01")))
        val accounts = FakeAccountServiceClient(byId = emptyMap())
        val domestic = FakeDomesticPaymentServiceClient(emptyMap())
        val port = RealPaymentConfirmationReadPort(consent, accounts, sepa, domestic)

        val result = port.getPaymentConfirmation(ctx, paymentId.toString())

        assertThat(result.path("rail").asText()).isEqualTo("SEPA")
        assertThat(result.path("debtorIban").asText()).isEqualTo("CZ01")
    }

    @Test
    fun `falls through to domestic when SEPA has never heard of the id, resolving the IBAN via account-service`() {
        val consent = FakeConsentValidateClient(valid = true, grantedAccounts = listOf("CZ01"))
        val debtorAccountId = UUID.randomUUID()
        val accounts = FakeAccountServiceClient(byId = mapOf(debtorAccountId to accountJson("CZ01")))
        val domestic = FakeDomesticPaymentServiceClient(mapOf(paymentId to domesticPaymentJson(debtorAccountId)))
        val port =
            RealPaymentConfirmationReadPort(consent, accounts, FakeSepaPaymentServiceClient(emptyMap()), domestic)

        val result = port.getPaymentConfirmation(ctx, paymentId.toString())

        assertThat(result.path("rail").asText()).isEqualTo("DOMESTIC")
    }

    @Test
    fun `neither rail knowing the id is reported as not found, not as a SEPA-specific 404`() {
        val port = RealPaymentConfirmationReadPort(
            FakeConsentValidateClient(valid = true, grantedAccounts = listOf("CZ01")),
            FakeAccountServiceClient(byId = emptyMap()),
            FakeSepaPaymentServiceClient(emptyMap()),
            FakeDomesticPaymentServiceClient(emptyMap()),
        )

        assertThatThrownBy { port.getPaymentConfirmation(ctx, paymentId.toString()) }
            .isInstanceOf(NoSuchElementException::class.java)
    }

    @Test
    fun `a malformed payment id is an invalid-argument error, not a lookup failure`() {
        val port = RealPaymentConfirmationReadPort(
            FakeConsentValidateClient(valid = true),
            FakeAccountServiceClient(byId = emptyMap()),
            FakeSepaPaymentServiceClient(emptyMap()),
            FakeDomesticPaymentServiceClient(emptyMap()),
        )

        assertThatThrownBy { port.getPaymentConfirmation(ctx, "not-a-uuid") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `denies when the debtor account is not within the granted consent scope`() {
        val consent = FakeConsentValidateClient(valid = true, grantedAccounts = listOf("CZ99-different"))
        val sepa = FakeSepaPaymentServiceClient(mapOf(paymentId to sepaPaymentJson("CZ01")))
        val accounts = FakeAccountServiceClient(byId = emptyMap())
        val domestic = FakeDomesticPaymentServiceClient(emptyMap())
        val port = RealPaymentConfirmationReadPort(consent, accounts, sepa, domestic)

        assertThatThrownBy { port.getPaymentConfirmation(ctx, paymentId.toString()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("not within the granted consent scope")
    }

    @Test
    fun `an invalid consent denies before either payment service is ever called`() {
        val sepa = FakeSepaPaymentServiceClient(mapOf(paymentId to sepaPaymentJson("CZ01")))
        val port = RealPaymentConfirmationReadPort(
            FakeConsentValidateClient(valid = false),
            FakeAccountServiceClient(byId = emptyMap()),
            sepa,
            FakeDomesticPaymentServiceClient(emptyMap()),
        )

        assertThatThrownBy { port.getPaymentConfirmation(ctx, paymentId.toString()) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(sepa.called).isFalse()
    }

    private fun sepaPaymentJson(debtorIban: String): JsonNode = mapper.createObjectNode()
        .put("id", paymentId.toString())
        .put("debtorIban", debtorIban)
        .put("status", "COMPLETED")

    private fun domesticPaymentJson(debtorAccountId: UUID): JsonNode = mapper.createObjectNode()
        .put("id", paymentId.toString())
        .put("debtorAccountId", debtorAccountId.toString())
        .put("status", "COMPLETED")

    private fun accountJson(iban: String): JsonNode = mapper.createObjectNode().put("accountNumber", iban)
}

@Vetoed
private class FakeSepaPaymentServiceClient(private val byId: Map<UUID, JsonNode>) : SepaPaymentServiceClient {
    var called: Boolean = false

    override fun getPayment(paymentId: UUID): JsonNode {
        called = true
        return byId[paymentId] ?: throw NotFoundException()
    }
}

@Vetoed
private class FakeDomesticPaymentServiceClient(private val byId: Map<UUID, JsonNode>) : DomesticPaymentServiceClient {
    override fun getPayment(paymentId: UUID): JsonNode = byId[paymentId] ?: throw NotFoundException()
}
