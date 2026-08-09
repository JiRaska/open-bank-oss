// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.mcp.application.port.out.AccountReadPort
import com.openbank.mcp.application.port.out.ConsentContext
import com.openbank.mcp.application.port.out.PaymentConfirmationReadPort
import com.openbank.mcp.application.port.out.ProposalPort
import com.openbank.mcp.application.port.out.StatementReadPort
import com.openbank.mcp.infrastructure.read.StubMarketingReachPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * ADR-0209 D5 — the campaign-copilot's read-only tool.
 *
 * The point of this file is the FIRST test. `count_marketing_consents` is registered and callable by
 * the registry, and no agent charter grants `query.marketing.readonly`, so the ADR-0034 PDP denies
 * every call today. That is the intended shipping state, and "deny-by-default holds" is worth nothing
 * as a claim in a PR body — it has to be an assertion against the file that decides it.
 *
 * When the grant lands (agents.yaml charter + tool_tiers + rego), the first test goes RED and must be
 * changed deliberately, which is the whole reason it is written against `agents.yaml` rather than
 * against a constant. A grant that slips in without anyone noticing is the failure this prevents.
 */
class McpCampaignToolTest {

    private val mapper = ObjectMapper()
    private val registry = McpToolRegistry(
        accounts = REFUSING_ACCOUNTS,
        statements = REFUSING_STATEMENTS,
        paymentConfirmations = REFUSING_PAYMENT_CONFIRMATIONS,
        proposals = REFUSING_PROPOSALS,
        marketingReach = StubMarketingReachPort(mapper),
        masker = McpPiiMasker(mapper),
        mapper = mapper,
    )

    private companion object {
        // This test never exercises the consent-scoped reads; binding them to something that throws
        // makes that explicit, so a future edit that accidentally routes a campaign tool through the
        // account port fails loudly instead of returning a plausible empty result.
        private val REFUSING_ACCOUNTS = object : AccountReadPort {
            override fun listAccounts(consentContext: ConsentContext) = fail()
            override fun getBalance(consentContext: ConsentContext, accountId: String) = fail()
            override fun listTransactions(consentContext: ConsentContext, accountId: String, limit: Int) = fail()
            override fun listConsents(consentContext: ConsentContext) = fail()
        }
        private val REFUSING_STATEMENTS = object : StatementReadPort {
            override fun getStatementSummary(
                consentContext: ConsentContext,
                accountId: String,
                currency: String?,
                legalSequence: Long?,
            ) = fail()
        }
        private val REFUSING_PAYMENT_CONFIRMATIONS = object : PaymentConfirmationReadPort {
            override fun getPaymentConfirmation(consentContext: ConsentContext, paymentId: String) = fail()
        }
        private val REFUSING_PROPOSALS = object : ProposalPort {
            override fun proposePayment(consentContext: ConsentContext, request: JsonNode) = fail()
        }
        private fun fail(): Nothing = throw AssertionError("the campaign tool must not reach a consent-scoped port")
    }

    private val agentsYaml: String by lazy {
        // Walk up to the repo root: the test's working directory is the module, the file is fleet-wide.
        var dir = File(".").canonicalFile
        while (dir.parentFile != null && !File(dir, "openbank-libs/governance/agents.yaml").exists()) {
            dir = dir.parentFile
        }
        File(dir, "openbank-libs/governance/agents.yaml").readText()
    }

    @Test
    fun `no charter grants query_marketing_readonly, so the PDP denies every call today`() {
        // Asserted on the governance file, not on a constant in this test — a constant would agree
        // with itself forever. If this fails, either the grant landed (update this test and say so in
        // the commit) or a capability was added to a charter that has no business holding it.
        assertThat(agentsYaml)
            .`as`("query.marketing.readonly must not be grantable until the ADR-0209 D5 charter lands")
            .doesNotContain("query.marketing.readonly")
    }

    @Test
    fun `the tool HAS a capability mapping, or the PDP is never reached at all`() {
        // McpEndpoint refuses a tool with no capability entry before it ever calls the PDP
        // ("no capability mapping"). Without this entry the policy would never be exercised, and the
        // eventual grant would be authorising a path nothing had tested.
        assertThat(registry.capabilities)
            .containsEntry("count_marketing_consents", "query.marketing.readonly")
    }

    @Test
    fun `the tool is advertised with a schema that takes no arguments`() {
        val tool = registry.tools.single { it.name == "count_marketing_consents" }

        @Suppress("UNCHECKED_CAST")
        val props = tool.inputSchema["properties"] as Map<String, Any>
        assertThat(props).isEmpty() // reach is a question about the bank, not about one customer
        assertThat(tool.inputSchema).doesNotContainKey("required")
    }

    @Test
    fun `the result carries counts only — no party id, no contact detail`() {
        // The aggregate IS the privacy control on this path (MarketingReachPort's kdoc), so this
        // asserts the shape rather than trusting the masker downstream. A future real implementation
        // that returned rows would fail here, which is the point.
        val json = mapper.writeValueAsString(StubMarketingReachPort(mapper).countMarketingConsents())
        for (forbidden in listOf("partyId", "email", "phone", "legalName", "consentId")) {
            assertThat(json).`as`(forbidden).doesNotContain(forbidden)
        }
        val node = mapper.readTree(json)
        assertThat(node["activeByScope"].fieldNames().asSequence().toList())
            .containsExactlyInAnyOrder(
                "MARKETING_COMMS_EMAIL",
                "MARKETING_COMMS_SMS",
                "MARKETING_COMMS_PUSH",
            )
        node["activeByScope"].forEach { assertThat(it.isInt).isTrue() }
    }

    @Test
    fun `the stub says it is a stub, so nobody sizes a campaign on it`() {
        val node = StubMarketingReachPort(mapper).countMarketingConsents()
        assertThat(node["phase"].asText()).isEqualTo("1-stub")
        assertThat(node["note"].asText()).contains("NOT REAL REACH")
    }

    @Test
    fun `it is read-only — ADR-0209 D5 bars this tool from ever gaining a write`() {
        // A write capability here would bypass ADR-0200 D5's campaign.activate four-eyes entirely.
        assertThat(registry.capabilities["count_marketing_consents"]).startsWith("query.")
        assertThat(registry.capabilities.keys.filter { it.startsWith("count_") })
            .containsExactly("count_marketing_consents")
    }
}
