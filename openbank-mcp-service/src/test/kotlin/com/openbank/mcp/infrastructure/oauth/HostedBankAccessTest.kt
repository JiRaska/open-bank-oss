// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.oauth

import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.Vendor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class HostedBankAccessTest {
    @Test
    fun `expired access is released only after a successful refresh and verified reload`(): Unit = runBlocking {
        val vendor = Vendor.CHATGPT
        val connection = UUID.randomUUID()
        val custody = mockk<HostedBankCustody>()
        val exchange = mockk<HostedBankRefreshExchange>()
        val replacement = mockk<HostedBankJwtVerifier.OwnedTokens>()
        val binding = HostedBankDpopBinding { "vendor-pin" }
        coEvery { custody.loadActive(vendor, connection, "vendor-pin") } returnsMany listOf(null, null, replacement)
        coEvery { custody.refreshActive(vendor, connection, "vendor-pin", exchange) } returnsMany listOf(false, true)

        val access = HostedBankAccess(custody, binding, exchange)
        assertThat(access.load(vendor, connection)).isNull()
        assertThat(access.load(vendor, connection)).isSameAs(replacement)
        coVerify(exactly = 3) { custody.loadActive(vendor, connection, "vendor-pin") }
    }
}
