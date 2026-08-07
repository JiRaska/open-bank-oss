// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.mcp.application.port.out.AccountReadPort
import com.openbank.mcp.application.port.out.ConsentContext
import com.openbank.mcp.application.port.out.PaymentConfirmationReadPort
import com.openbank.mcp.application.port.out.ProposalPort
import com.openbank.mcp.application.port.out.StatementReadPort
import com.openbank.mcp.infrastructure.read.StubMarketingReachPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The `mcp-anonymous` charter advertises `data_scope.pii: masked` (agents.yaml). These assertions
 * are what makes that true rather than documentation (#2412): they fail against the pre-fix
 * `McpToolRegistry.call`, which serialized the read port's response verbatim into the model-facing
 * `ToolCallResult`.
 */
class McpPiiMaskingTest {

    private val mapper = ObjectMapper()
    private val masker = McpPiiMasker(mapper)
    private val ctx = ConsentContext("agent:mcp-anonymous", "11111111-2222-3333-4444-555555555555", emptyList())

    private val accountJson = """
        {
          "id": "6f1d2c3b-4a5e-4f60-8a71-9b0c1d2e3f40",
          "iban": "CZ6508000000192000145399",
          "holderName": "Jan Novak",
          "currency": "CZK",
          "status": "ACTIVE"
        }
    """.trimIndent()

    private val transactionsJson = """
        [
          {
            "id": "7a2e3f40-5b6c-4d7e-8f90-0a1b2c3d4e5f",
            "amount": "1250.00",
            "currency": "CZK",
            "counterpartyIban": "DE89370400440532013000",
            "counterpartyName": "Petra Svobodova",
            "remittanceInformation": "rent June, contact 777123456",
            "bookedAt": "2026-06-01T10:15:30Z"
          }
        ]
    """.trimIndent()

    private fun registry(payload: String) = McpToolRegistry(
        accounts = FixedReadPort(mapper.readTree(payload)),
        statements = DenyingStatementReadPort,
        paymentConfirmations = DenyingPaymentConfirmationReadPort,
        proposals = DenyingProposalPort,
        marketingReach = StubMarketingReachPort(mapper),
        masker = masker,
        mapper = mapper,
    )

    @Test
    fun `list_accounts never returns a raw IBAN or holder name to the model`() {
        val text = registry(accountJson).call("list_accounts", mapper.createObjectNode(), ctx).content.single().text

        assertFalse(text.contains("CZ6508000000192000145399"), "raw IBAN leaked to the model: $text")
        assertFalse(text.contains("Jan Novak"), "raw holder name leaked to the model: $text")
        assertTrue(text.contains("****5399"), "expected a correlatable IBAN tail, got: $text")
        // Non-PII payload must survive: masking that ate the answer would be a different bug.
        assertTrue(text.contains("ACTIVE") && text.contains("CZK"), "non-PII fields were masked: $text")
        assertTrue(text.contains("6f1d2c3b-4a5e-4f60-8a71-9b0c1d2e3f40"), "surrogate id was masked: $text")
    }

    @Test
    fun `list_transactions never returns a counterparty name or a free-text narrative`() {
        val args = mapper.createObjectNode().put("accountId", "CZ6508000000192000145399")
        val text = registry(transactionsJson).call("list_transactions", args, ctx).content.single().text

        assertFalse(text.contains("Petra Svobodova"), "counterparty name leaked: $text")
        assertFalse(text.contains("777123456"), "narrative PII leaked: $text")
        assertFalse(text.contains("DE89370400440532013000"), "counterparty IBAN leaked: $text")
        assertTrue(text.contains("1250.00"), "the amount — the point of the read — was masked: $text")
    }

    @Test
    fun `an IBAN-shaped value is masked wherever it appears, whatever the field is called`() {
        val node = mapper.readTree("""{"somethingElse":"GB33BUKB20201555555555","free":["CZ6508000000192000145399"]}""")

        val masked = masker.mask(node)

        assertEquals("****5555", masked.path("somethingElse").asText())
        assertEquals("****5399", masked.path("free").path(0).asText())
    }

    /**
     * Guards the object-recursion path itself: masking walks an object's properties and rebuilds
     * it, so a nested object must be descended into and every sibling key preserved. A recursion
     * that silently visited only the top level would still pass the flat-payload assertions above.
     */
    @Test
    fun `masking descends into a nested object and preserves every key`() {
        val node = mapper.readTree(
            """
            {
              "status": "ACTIVE",
              "owner": {"holderName": "Jan Novak", "iban": "CZ6508000000192000145399", "currency": "CZK"}
            }
            """.trimIndent(),
        )

        val masked = masker.mask(node)

        assertEquals("***", masked.path("owner").path("holderName").asText())
        assertEquals("****5399", masked.path("owner").path("iban").asText())
        assertEquals("CZK", masked.path("owner").path("currency").asText())
        assertEquals("ACTIVE", masked.path("status").asText())
        assertEquals(2, masked.size(), "a key was dropped while rebuilding the object: $masked")
        assertEquals(3, masked.path("owner").size(), "a nested key was dropped: $masked")
    }

    private class FixedReadPort(private val payload: JsonNode) : AccountReadPort {
        override fun listAccounts(consentContext: ConsentContext): JsonNode = payload
        override fun getBalance(consentContext: ConsentContext, accountId: String): JsonNode = payload
        override fun listTransactions(consentContext: ConsentContext, accountId: String, limit: Int): JsonNode = payload
        override fun listConsents(consentContext: ConsentContext): JsonNode = payload
    }

    private object DenyingProposalPort : ProposalPort {
        override fun proposePayment(consentContext: ConsentContext, request: JsonNode): JsonNode =
            error("not exercised by this test")
    }

    private object DenyingStatementReadPort : StatementReadPort {
        override fun getStatementSummary(
            consentContext: ConsentContext,
            accountId: String,
            currency: String?,
            legalSequence: Long?,
        ): JsonNode = error("not exercised by this test")
    }

    private object DenyingPaymentConfirmationReadPort : PaymentConfirmationReadPort {
        override fun getPaymentConfirmation(consentContext: ConsentContext, paymentId: String): JsonNode =
            error("not exercised by this test")
    }
}
