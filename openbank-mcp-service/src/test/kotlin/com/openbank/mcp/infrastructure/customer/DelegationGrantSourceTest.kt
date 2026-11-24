// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.customer

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class DelegationGrantSourceTest {
    @Test
    fun `resolve sends the connection id only when the admitted key carries it`() {
        val mapper = ObjectMapper()
        val bodies = mutableListOf<String>()
        val source = DelegationGrantSource(
            object : DelegationGrantClient {
                override fun resolve(request: ResolveMcpClientGrantRequest): ResolveMcpClientGrantResponse {
                    bodies += mapper.writeValueAsString(request)
                    return ResolveMcpClientGrantResponse(emptyList())
                }
            },
        )
        val key = GrantKey(UUID.randomUUID(), "client", "A".repeat(43))
        val connectionId = UUID.randomUUID()

        source.resolve(key)
        source.resolve(key.copy(connectionId = connectionId))

        val first = mapper.readTree(bodies[0])
        assertThat(first.has("connectionId")).isFalse()
        assertThat(first.path("grantorPartyId").asText()).isEqualTo(key.partyId.toString())
        assertThat(first.path("clientId").asText()).isEqualTo(key.clientId)
        assertThat(first.path("keyThumbprint").asText()).isEqualTo(key.keyThumbprint)
        assertThat(mapper.readTree(bodies[1]).path("connectionId").asText()).isEqualTo(connectionId.toString())
    }
}
