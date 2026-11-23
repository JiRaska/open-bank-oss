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
import com.openbank.mcp.infrastructure.config.AccountRefKeyConfig
import com.openbank.mcp.infrastructure.read.StubMarketingReachPort
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Optional

/** #16678: caller-scoped account handles, at the registry and at the key boundary. */
class McpAccountRefsTest {

    private val mapper = ObjectMapper()
    private val key = ByteArray(32) { it.toByte() }
    private val refs = McpAccountRefs(key)
    private val iban = "CZ6508000000192000145399"
    private val otherIban = "DE89370400440532013000"
    private val ctx = ConsentContext("agent:a", "consent-1", emptyList(), sessionId = "s1")

    /** Records the IBAN each read was asked for, so a test can see what the ref resolved to. */
    private inner class RecordingPort : AccountReadPort {
        val asked = mutableListOf<String>()
        override fun listAccounts(consentContext: ConsentContext): JsonNode = mapper.readTree(
            """[{"id":"6f1d2c3b-4a5e-4f60-8a71-9b0c1d2e3f40","iban":"$iban","holderName":"Jan Novak","currency":"CZK"},
                {"id":"7a2e3f40-5b6c-4d7e-8f90-0a1b2c3d4e5f","iban":"$otherIban","currency":"EUR"}]""",
        )
        override fun getBalance(consentContext: ConsentContext, accountId: String): JsonNode {
            asked += accountId
            return mapper.readTree("""{"iban":"$accountId","balance":"10.00","currency":"CZK"}""")
        }
        override fun listTransactions(consentContext: ConsentContext, accountId: String, limit: Int): JsonNode {
            asked += accountId
            return mapper.createArrayNode()
        }
        override fun listConsents(consentContext: ConsentContext): JsonNode = mapper.createArrayNode()
    }

    private fun registry(port: AccountReadPort) = McpToolRegistry(
        accounts = port,
        statements = DenyingStatementReadPort,
        paymentConfirmations = DenyingPaymentConfirmationReadPort,
        proposals = DenyingProposalPort,
        marketingReach = StubMarketingReachPort(mapper),
        masker = McpPiiMasker(mapper),
        mapper = mapper,
        accountRefs = refs,
    )

    private fun refsIn(text: String) = Regex("acr_[A-Za-z0-9_-]{43}").findAll(text).map { it.value }.toList()

    @Test
    fun `list_accounts carries one ref per account and keeps every IBAN masked`() {
        val text = registry(RecordingPort()).call("list_accounts", mapper.createObjectNode(), ctx).content.single().text
        assertThat(refsIn(text)).containsExactly(refs.refFor(ctx, iban)!!, refs.refFor(ctx, otherIban)!!)
        assertThat(text).doesNotContain(iban).doesNotContain(otherIban).doesNotContain("Jan Novak")
        assertThat(text).contains("****5399").contains("****3000")
    }

    @Test
    fun `masked output is unchanged apart from the added accountRef`() {
        val port = RecordingPort()
        val withRefs = registry(port).call("list_accounts", mapper.createObjectNode(), ctx).content.single().text
        val stripped = Regex(",\"accountRef\":\"acr_[A-Za-z0-9_-]{43}\"").replace(withRefs, "")
        val legacy = McpUntrustedData.wrap(
            mapper.writeValueAsString(
                McpToolResultSanitiser.sanitise(McpPiiMasker(mapper).mask(port.listAccounts(ctx))),
            ),
        )
        assertThat(stripped).isEqualTo(legacy)
    }

    @Test
    fun `a ref chains list_accounts into get_balance and resolves to the right account`() {
        val port = RecordingPort()
        val registry = registry(port)
        val listed = registry.call("list_accounts", mapper.createObjectNode(), ctx).content.single().text
        val second = refsIn(listed)[1]
        val result = registry.call("get_balance", mapper.createObjectNode().put("accountRef", second), ctx)
        assertThat(result.isError).isFalse()
        assertThat(port.asked).containsExactly(otherIban)
        assertThat(result.content.single().text).doesNotContain(otherIban)
    }

    @Test
    fun `a ref from another agent, consent or session is refused before the read`() {
        val ref = refs.refFor(ctx, iban)!!
        listOf(
            ctx.copy(agentId = "agent:b"),
            ctx.copy(consentId = "consent-2"),
            ctx.copy(sessionId = "s2"),
            ctx.copy(sessionId = null),
        ).forEach { other ->
            val port = RecordingPort()
            assertThatThrownBy {
                registry(port).call("get_balance", mapper.createObjectNode().put("accountRef", ref), other)
            }.isInstanceOf(IllegalArgumentException::class.java).hasMessage("accountRef is not valid for this caller")
            assertThat(port.asked).`as`("%s", other).isEmpty()
        }
    }

    @Test
    fun `a ref minted under another key is refused`() {
        val foreign = McpAccountRefs(ByteArray(32) { 7 }).refFor(ctx, iban)!!
        val port = RecordingPort()
        assertThatThrownBy {
            registry(port).call("list_transactions", mapper.createObjectNode().put("accountRef", foreign), ctx)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(port.asked).isEmpty()
    }

    @Test
    fun `a tampered or malformed ref is refused`() {
        val own = refs.refFor(ctx, iban)!!
        val tampered = own.substring(0, 10) + (if (own[10] == 'x') 'y' else 'x') + own.substring(11)
        listOf(tampered, own + "A", own.drop(4), iban, "").forEach { bad ->
            assertThat(refs.resolve(ctx, bad, listOf(iban, otherIban))).`as`(bad).isNull()
        }
        assertThat(refs.resolve(ctx, own, listOf(otherIban, iban))).isEqualTo(iban)
    }

    @Test
    fun `a ref belongs to one account only and differs per caller`() {
        assertThat(refs.refFor(ctx, iban)!!).isNotEqualTo(refs.refFor(ctx, otherIban)!!)
        assertThat(refs.refFor(ctx, iban)!!).isNotEqualTo(refs.refFor(ctx.copy(agentId = "agent:b"), iban)!!)
        assertThat(refs.refFor(ctx, iban)!!).isEqualTo(McpAccountRefs(key).refFor(ctx, iban)!!)
        assertThat(refs.refFor(ctx, iban)!!).doesNotContain(iban.takeLast(4))
    }

    @Test
    fun `a raw IBAN accountId is still accepted, but not together with a ref`() {
        val port = RecordingPort()
        registry(port).call("get_balance", mapper.createObjectNode().put("accountId", iban), ctx)
        assertThat(port.asked).containsExactly(iban)
        assertThatThrownBy {
            registry(port).call(
                "get_balance",
                mapper.createObjectNode().put("accountId", otherIban).put("accountRef", refs.refFor(ctx, iban)!!),
                ctx,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `an upstream accountRef carrying an IBAN is masked like any other field`() {
        // `-` is base64url AND a word boundary for the in-text IBAN detector: a ref-SHAPED value can
        // smuggle an IBAN, so nothing exempts the field. Only the server's own ref is attached, last.
        val smuggled = "acr_abcdefghijklmnopqrst-" + "DE89370400440532013000"
        val port = object : AccountReadPort by RecordingPort() {
            override fun listAccounts(consentContext: ConsentContext): JsonNode = mapper.readTree(
                """[{"id":"6f1d2c3b-4a5e-4f60-8a71-9b0c1d2e3f40","accountRef":"$smuggled"},
                    {"iban":"$iban","accountRef":"$smuggled"}]""",
            )
        }
        val text = registry(port).call("list_accounts", mapper.createObjectNode(), ctx).content.single().text
        assertThat(text).doesNotContain("DE89370400440532013000").doesNotContain(smuggled)
        // On the object with a real iban, the server's ref replaces the upstream value.
        assertThat(refsIn(text)).containsExactly(refs.refFor(ctx, iban)!!)
    }

    @Test
    fun `a caller without a session id gets no refs and can resolve none`() {
        val sidless1 = ctx.copy(sessionId = null)
        val sidless2 = ctx.copy(sessionId = "")
        val port = RecordingPort()
        listOf(sidless1, sidless2).forEach { c ->
            val text = registry(port).call("list_accounts", mapper.createObjectNode(), c).content.single().text
            assertThat(refsIn(text)).`as`("%s", c).isEmpty()
            assertThat(refs.refFor(c, iban)).isNull()
            assertThat(text).contains("****5399")
        }
        // Even a ref minted for a real session cannot be used by either sid-less caller.
        val sessionRef = refs.refFor(ctx, iban)!!
        listOf(sidless1, sidless2).forEach { c ->
            assertThat(refs.resolve(c, sessionRef, listOf(iban))).isNull()
            assertThatThrownBy {
                registry(port).call("get_balance", mapper.createObjectNode().put("accountRef", sessionRef), c)
            }.isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(port.asked).isEmpty()
    }

    @Test
    fun `a sid-less caller losing chaining is counted, a caller with a session is not`() {
        val registry = SimpleMeterRegistry()
        val config = AccountRefKeyConfig(Optional.of("y".repeat(32)), registry)
        val counted = config.accountRefs()
        fun count(op: String) = registry.find(AccountRefKeyConfig.UNAVAILABLE_COUNTER)
            .tags("reason", "no_session", "op", op).counter()?.count() ?: 0.0
        val raw = mapper.readTree("""[{"iban":"$iban"}]""")
        val sidless = ctx.copy(sessionId = null)

        counted.attach(ctx, raw, raw)
        counted.resolve(ctx, counted.refFor(ctx, iban)!!, listOf(iban))
        assertThat(count(McpAccountRefs.OP_ISSUE) + count(McpAccountRefs.OP_RESOLVE)).isEqualTo(0.0)

        counted.attach(sidless, raw, raw)
        // A result with no account in it withholds nothing, so it is not counted.
        counted.attach(sidless, mapper.readTree("""{"status":"ok"}"""), mapper.readTree("""{"status":"ok"}"""))
        counted.resolve(sidless, counted.refFor(ctx, iban)!!, listOf(iban))
        assertThat(count(McpAccountRefs.OP_ISSUE)).isEqualTo(1.0)
        assertThat(count(McpAccountRefs.OP_RESOLVE)).isEqualTo(1.0)
        assertThat(registry.find(AccountRefKeyConfig.EPHEMERAL_GAUGE).gauge()?.value()).isEqualTo(0.0)
    }

    @Test
    fun `an absent secret yields a working ephemeral key, distinct per process`() {
        val a = AccountRefKeyConfig.fromSecret(Optional.empty())
        val b = AccountRefKeyConfig.fromSecret(Optional.empty())
        assertThat(a.ephemeral).isTrue()
        val ref = a.refs.refFor(ctx, iban)!!
        assertThat(a.refs.resolve(ctx, ref, listOf(iban))).isEqualTo(iban)
        // A "restarted pod" (new ephemeral key) refuses the old ref: fails closed.
        assertThat(b.refs.resolve(ctx, ref, listOf(iban))).isNull()
    }

    @Test
    fun `a configured secret is shared across instances`() {
        val secret = Optional.of("y".repeat(32))
        val a = AccountRefKeyConfig.fromSecret(secret)
        assertThat(a.ephemeral).isFalse()
        val ref = a.refs.refFor(ctx, iban)!!
        assertThat(AccountRefKeyConfig.fromSecret(secret).refs.resolve(ctx, ref, listOf(iban))).isEqualTo(iban)
    }

    @Test
    fun `a configured but short secret refuses boot without echoing it`() {
        listOf("", "x".repeat(31)).forEach { short ->
            assertThatThrownBy { AccountRefKeyConfig.fromSecret(Optional.of(short)) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageNotContaining("xxxx")
        }
        assertThatThrownBy { McpAccountRefs(ByteArray(31)) }.isInstanceOf(IllegalArgumentException::class.java)
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
