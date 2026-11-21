// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.out.DelegationRepository
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationGranteeType
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.DelegationStatus
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** ADR-0383 D3: the triple is the whole key, and only an ACTIVE, in-window MCP_CLIENT grant matches. */
class McpClientGrantResolverTest {
    private val repository: DelegationRepository = mockk()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-11-18T12:00:00Z"), ZoneOffset.UTC)
    private val now: OffsetDateTime = OffsetDateTime.now(clock)
    private val party: UUID = UUID.randomUUID()
    private val clientId = "mcp-local-reference"
    private val jkt = "NzbLsXh8uDCcd-6MNwXF4W_7noWXFZAfHkxZsRGC9Xs"
    private val resolver = McpClientGrantResolver(repository, clock)

    private fun grant(
        grantor: UUID = party,
        status: DelegationStatus = DelegationStatus.ACTIVE,
        thumbprint: String = jkt,
        validTo: OffsetDateTime? = now.plusDays(30),
    ) = DelegationGrant(
        grantorPartyId = grantor,
        granteePartyId = DelegationGrant.mcpClientGranteeId(clientId),
        granteeType = DelegationGranteeType.MCP_CLIENT,
        granteeClientId = clientId,
        granteeKeyThumbprint = thumbprint,
        resourceType = DelegationResourceType.ACCOUNT,
        resourceId = UUID.randomUUID(),
        capabilities = setOf(DelegationCapability.ACCOUNT_READ_BALANCES),
        validFrom = now.minusDays(1),
        validTo = validTo,
        status = status,
        createdAt = now,
        updatedAt = now,
    )

    private fun given(vararg grants: DelegationGrant): List<DelegationGrant> {
        coEvery { repository.findActiveMcpClientGrants(party, clientId, jkt) } returns grants.toList()
        return runBlocking { resolver.resolve(party, clientId, jkt) }
    }

    @Test
    fun `an active grant for the exact triple is returned`() {
        val g = grant()
        assertThat(given(g)).containsExactly(g)
    }

    @Test
    fun `a grant made by another party to the same client never matches (shared-grantee defect)`() {
        assertThat(given(grant(grantor = UUID.randomUUID()))).isEmpty()
    }

    @Test
    fun `a grant bound to another DPoP key does not match`() {
        assertThat(given(grant(thumbprint = "A".repeat(43)))).isEmpty()
    }

    @Test
    fun `revoked, suspended and offered grants do not match`() {
        val statuses = listOf(DelegationStatus.REVOKED, DelegationStatus.SUSPENDED, DelegationStatus.OFFERED)
        assertThat(given(*statuses.map { grant(status = it) }.toTypedArray())).isEmpty()
    }

    @Test
    fun `an expired grant does not match`() {
        assertThat(given(grant(validTo = now.minusSeconds(1)))).isEmpty()
    }

    @Test
    fun `no grant at all is an empty answer`() {
        assertThat(given()).isEmpty()
    }
}
