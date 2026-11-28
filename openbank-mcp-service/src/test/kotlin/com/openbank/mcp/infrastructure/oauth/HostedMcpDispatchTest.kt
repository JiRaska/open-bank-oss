// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.oauth

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.mcp.infrastructure.oauth.HostedMcpDispatch.Outcome
import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.Vendor
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class HostedMcpDispatchTest {
    @Test
    fun `active consent and read scope are required for discovery and calls`(): Unit = runBlocking {
        val mapper = ObjectMapper()
        val grant = PgHostedVendorGrants.Identity(Vendor.CHATGPT, UUID.randomUUID())
        val grants = mockk<HostedVendorGrants>()
        coEvery { grants.admit("active") } returns grant
        coEvery { grants.admit("revoked") } returns null
        val expired = PgHostedVendorGrants.Identity(Vendor.CHATGPT, UUID.randomUUID())
        coEvery { grants.admit("bank-expired") } returns expired
        val bankAccess = mockk<HostedBankAccess>()
        val bankTokens = mockk<HostedBankJwtVerifier.OwnedTokens>()
        every { bankTokens.access } returns "bank-access-fixture"
        coEvery { bankAccess.load(grant.vendor, grant.connectionId) } returns bankTokens
        coEvery { bankAccess.load(expired.vendor, expired.connectionId) } returns null
        var upstreamCalls = 0
        val upstream = HostedBankMcpUpstream { vendor, bankAccess, _, protocolVersion ->
            assertThat(protocolVersion).isEqualTo("2025-03-26")
            assertThat(vendor).isEqualTo(grant.vendor)
            assertThat(bankAccess).isEqualTo("bank-access-fixture")
            upstreamCalls++
            val body = mapper.readTree(
                """{"result":{"tools":[{"name":"list_accounts"},{"name":"get_balance"},{"name":"propose_payment"}]}}""",
            )
            HostedBankMcpUpstream.Reply(200, body)
        }
        val dispatch = HostedMcpDispatch(grants, bankAccess, upstream)
        val list = mapper.readTree("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        val forbidden = mapper.readTree(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"propose_payment"}}""",
        )
        assertThat(dispatch.dispatch("revoked", list)).isEqualTo(Outcome.Refused.INVALID_TOKEN)
        assertThat(dispatch.dispatch("bank-expired", list)).isEqualTo(Outcome.Refused.INVALID_TOKEN)
        assertThat(dispatch.dispatch("active", forbidden)).isEqualTo(Outcome.Refused.OUTSIDE_SCOPE)
        assertThat(upstreamCalls).isZero()

        val response = dispatch.dispatch("active", list, "2025-03-26") as Outcome.Forwarded
        val names = response.reply.body!!.path("result").path("tools").map { it.path("name").textValue() }
        assertThat(names).containsExactly("list_accounts", "get_balance")
        assertThat(upstreamCalls).isEqualTo(1)
    }
}
