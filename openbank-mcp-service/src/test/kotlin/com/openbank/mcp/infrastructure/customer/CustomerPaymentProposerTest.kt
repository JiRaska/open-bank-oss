// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.customer

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

/** ADR-0383 D6: the customer propose tool's validation, idempotency key and store-answer mapping. */
class CustomerPaymentProposerTest {
    private val mapper = ObjectMapper()
    private val store: PaymentProposalStore = mockk()
    private val proposer = CustomerPaymentProposer(store, mapper)
    private val key = GrantKey(UUID.fromString(PARTY), "mcp-local-reference", JKT)

    private fun args(vararg overrides: Pair<String, Any?>): JsonNode {
        val base = mutableMapOf<String, Any?>(
            "debtorAccountId" to ACCOUNT,
            "creditorIban" to CZ_IBAN,
            "creditorName" to "Jan Novak",
            "amount" to "125.5",
            "currency" to "CZK",
            "reference" to "Invoice 42",
            "clientRequestId" to "req-00000001",
        )
        overrides.forEach { (k, v) -> if (v == null) base.remove(k) else base[k] = v }
        return mapper.valueToTree(base)
    }

    private fun refusedFor(vararg overrides: Pair<String, Any?>) =
        assertThatThrownBy { CustomerPaymentProposer.parse(args(*overrides)) }
            .isInstanceOf(IllegalArgumentException::class.java)

    @Test
    fun `a valid instruction is canonicalised to the store's form`() {
        val i = CustomerPaymentProposer.parse(args())
        assertThat(i.amount).isEqualTo("125.50")
        assertThat(i.operationType).isEqualTo("DOMESTIC_PAYMENT")
        assertThat(i.debtorAccountId).isEqualTo(UUID.fromString(ACCOUNT))
        assertThat(CustomerPaymentProposer.parse(args("currency" to "EUR", "creditorIban" to DE_IBAN)).operationType)
            .isEqualTo("SEPA_PAYMENT")
    }

    @ParameterizedTest
    @ValueSource(strings = ["1e3", "-1", "+1", "0", "0.00", "0.001", "1,00", "1000000.01", "01", " 1", "", "NaN"])
    fun `an amount outside the store's bounds is refused`(amount: String) {
        refusedFor("amount" to amount)
    }

    @Test
    fun `the ceiling itself is accepted`() {
        assertThat(CustomerPaymentProposer.parse(args("amount" to "1000000")).amount).isEqualTo("1000000.00")
    }

    @Test
    fun `a JSON number amount is refused - money never passes through a double`() {
        refusedFor("amount" to 12.5)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "CZ6508000000192000145398", // bad mod-97
            "cz6508000000192000145399", // lowercase
            "CZ65 0800 0000 1920 0014 5399", // spaced
            "not-an-iban",
        ],
    )
    fun `an IBAN that fails McpInputBounds is refused`(iban: String) {
        refusedFor("creditorIban" to iban)
    }

    @Test
    fun `currency is CZK or EUR only and CZK needs a Czech IBAN`() {
        refusedFor("currency" to "USD")
        refusedFor("currency" to "czk")
        refusedFor("creditorIban" to DE_IBAN) // CZK default
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Jan‮Novak", // bidi override (Cf)
            "Jan​Novak", // zero-width space (Cf)
            "Jan Novak", // line separator (Zl)
            "Jan\u0007Novak", // control (Cc)
            "<b>Jan</b>",
            "[Jan](x)",
            "`Jan`",
            "see https://example.invalid",
        ],
    )
    fun `creditor name and reference carry the store's Unicode and markup rule`(text: String) {
        refusedFor("creditorName" to text)
        refusedFor("reference" to text)
    }

    @Test
    fun `free text is NFC-normalised before it is sent`() {
        val decomposed = "José" // e + combining acute
        val i = CustomerPaymentProposer.parse(args("creditorName" to decomposed))
        assertThat(i.creditorName).isEqualTo("José")
    }

    @Test
    fun `length caps match the store`() {
        assertThat(CustomerPaymentProposer.parse(args("creditorName" to "a".repeat(70))).creditorName).hasSize(70)
        refusedFor("creditorName" to "a".repeat(71))
        refusedFor("reference" to "a".repeat(141))
        refusedFor("creditorName" to "   ")
    }

    @Test
    fun `missing, mistyped and unknown arguments are refused`() {
        refusedFor("creditorName" to null)
        refusedFor("clientRequestId" to null)
        refusedFor("clientRequestId" to "short")
        refusedFor("debtorAccountId" to "not-a-uuid")
        refusedFor("debtorAccountId" to 7)
        refusedFor("approve" to true)
        assertThat(CustomerPaymentProposer.parse(args("reference" to null)).reference).isNull()
    }

    @Test
    fun `the idempotency key is stable for a retry and differs for every other input`() {
        val k = CustomerPaymentProposer.idempotencyKey(key, "sid-1", "req-00000001")
        assertThat(k).matches("^[0-9a-f]{64}$")
        assertThat(CustomerPaymentProposer.idempotencyKey(key, "sid-1", "req-00000001")).isEqualTo(k)
        assertThat(
            setOf(
                CustomerPaymentProposer.idempotencyKey(key, "sid-2", "req-00000001"),
                CustomerPaymentProposer.idempotencyKey(key, "sid-1", "req-00000002"),
                CustomerPaymentProposer.idempotencyKey(key, null, "req-00000001"),
                CustomerPaymentProposer.idempotencyKey(key.copy(clientId = "other"), "sid-1", "req-00000001"),
                CustomerPaymentProposer.idempotencyKey(key.copy(partyId = UUID.randomUUID()), "sid-1", "req-00000001"),
                CustomerPaymentProposer.idempotencyKey(key.copy(keyThumbprint = "x".repeat(43)), "sid-1", "req-00000001"),
            ),
        ).hasSize(6).doesNotContain(k)
    }

    @Test
    fun `the store gets the grant triple, the canonical instruction and the derived key`() {
        val sentKey = slot<String>()
        val sent = slot<CreatePaymentProposalRequest>()
        every { store.create(capture(sentKey), capture(sent)) } returns receipt()
        val out = proposer.propose(CustomerPaymentProposer.parse(args()), key, "sid-1")
        assertThat(out).isInstanceOf(CustomerPaymentProposer.Result.Proposed::class.java)
        assertThat(sentKey.captured).isEqualTo(CustomerPaymentProposer.idempotencyKey(key, "sid-1", "req-00000001"))
        with(sent.captured) {
            assertThat(partyId).isEqualTo(key.partyId)
            assertThat(mcpClientId).isEqualTo(key.clientId)
            assertThat(keyThumbprint).isEqualTo(key.keyThumbprint)
            assertThat(amount).isEqualTo("125.50")
            assertThat(operationType).isEqualTo("DOMESTIC_PAYMENT")
        }
    }

    @Test
    fun `a retry sends the same key twice - the store, not a second proposal, answers it`() {
        val keys = mutableListOf<String>()
        every { store.create(capture(keys), any()) } returns receipt()
        repeat(2) { proposer.propose(CustomerPaymentProposer.parse(args()), key, "sid-1") }
        assertThat(keys).hasSize(2)
        assertThat(keys.toSet()).hasSize(1)
    }

    @Test
    fun `the result is the id, PENDING and a bank-rendered summary - no model text`() {
        every { store.create(any(), any()) } returns receipt(summary = "Proposed to <script>")
        val body = (proposer.propose(CustomerPaymentProposer.parse(args()), key, null) as CustomerPaymentProposer.Result.Proposed).body
        assertThat(body.path("proposalId").asText()).isEqualTo(PROPOSAL)
        assertThat(body.path("status").asText()).isEqualTo("PENDING")
        assertThat(body.path("summary").asText()).contains("Nothing has been paid").doesNotContain("<script>")
            .doesNotContain("Jan Novak").doesNotContain("Invoice 42")
    }

    @ParameterizedTest
    @ValueSource(strings = ["APPROVED", "EXECUTED", "EXECUTION_FAILED", "REJECTED", ""])
    fun `any state but PENDING is refused, fail closed`(status: String) {
        every { store.create(any(), any()) } returns receipt(status = status)
        val out = proposer.propose(CustomerPaymentProposer.parse(args()), key, null)
        assertThat(out).isInstanceOf(CustomerPaymentProposer.Result.Refused::class.java)
        assertThat((out as CustomerPaymentProposer.Result.Refused).unavailable).isTrue()
    }

    @Test
    fun `store refusals map to fixed safe messages`() {
        fun refusal(status: Int, code: String?): CustomerPaymentProposer.Result.Refused {
            every { store.create(any(), any()) } throws ProposalStoreRefusal(status, code)
            return proposer.propose(CustomerPaymentProposer.parse(args()), key, null) as CustomerPaymentProposer.Result.Refused
        }
        assertThat(refusal(429, "PROPOSAL_LIMIT_REACHED").message).isEqualTo(CustomerPaymentProposer.LIMIT_REACHED)
        assertThat(refusal(409, "IDEMPOTENCY_KEY_REUSED").message).isEqualTo(CustomerPaymentProposer.REQUEST_ID_REUSED)
        assertThat(refusal(409, "IDEMPOTENCY_REQUEST_IN_PROGRESS").message).isEqualTo(CustomerPaymentProposer.IN_PROGRESS)
        assertThat(refusal(400, null).message).isEqualTo(CustomerPaymentProposer.INVALID_INSTRUCTION)
        // Expired / revoked / suspended grant: the store finds none. Drop the cached grant.
        val forbidden = refusal(403, "PROPOSAL_NOT_PERMITTED")
        assertThat(forbidden.message).isEqualTo("Not permitted")
        assertThat(forbidden.invalidateGrant).isTrue()
        assertThat(refusal(401, null).unavailable).isTrue()
    }

    @Test
    fun `a transport failure is unavailable, not success`() {
        every { store.create(any(), any()) } throws IllegalStateException("connection reset")
        val out = proposer.propose(CustomerPaymentProposer.parse(args()), key, null) as CustomerPaymentProposer.Result.Refused
        assertThat(out.unavailable).isTrue()
        assertThat(out.message).isNull()
        verify(exactly = 1) { store.create(any(), any()) }
    }

    private fun receipt(status: String = "PENDING", summary: String = "s") = McpProposalReceipt(
        proposalId = UUID.fromString(PROPOSAL),
        status = status,
        deduplicated = false,
        expiresAt = "2026-11-20T10:00:00Z",
        summary = summary,
    )

    companion object {
        const val PARTY = "d3d3d3d3-0000-4000-8000-000000000001"
        const val ACCOUNT = "d3d3d3d3-0000-4000-8000-000000000003"
        const val PROPOSAL = "d3d3d3d3-0000-4000-8000-000000000004"
        const val JKT = "0000000000000000000000000000000000000000000"
        const val CZ_IBAN = "CZ6508000000192000145399"
        const val DE_IBAN = "DE89370400440532013000"
    }
}
