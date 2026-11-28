// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.out.DelegationRepository
import com.openbank.delegation.application.port.out.McpIssuerProofReplayStore
import com.openbank.delegation.application.port.out.OwnershipVerdict
import com.openbank.delegation.application.port.out.ResourceOwnershipClient
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationGranteeType
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.DelegationStatus
import com.openbank.libs.security.ReplayClaim
import io.mockk.coEvery
import io.mockk.coVerify
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
    fun `two connections using one key get only their own grants`() {
        val first = grant().copy(mcpConnectionId = UUID.randomUUID())
        val second = grant().copy(mcpConnectionId = UUID.randomUUID())
        val legacy = grant()
        assertThat(given(first, second, legacy)).containsExactly(legacy)
        val resolved = runBlocking {
            resolver.resolveForConnection(party, clientId, jkt, requireNotNull(first.mcpConnectionId))
        }
        assertThat(resolved).containsExactly(first)
    }

    @Test
    fun `hosted lookup requires the exact connection and rejects DPoP grants`() {
        val connection = UUID.randomUUID()
        val hosted = grant().copy(mcpConnectionId = connection, granteeKeyThumbprint = null)
        val dpop = hosted.copy(granteeKeyThumbprint = jkt)
        val otherParty = hosted.copy(grantorPartyId = UUID.randomUUID())
        val otherConnection = hosted.copy(mcpConnectionId = UUID.randomUUID())
        coEvery {
            repository.findActiveHostedMcpClientGrants(party, clientId, connection)
        } returns listOf(hosted, dpop, otherParty, otherConnection)

        val resolved = runBlocking { resolver.resolveHostedConnection(party, clientId, connection) }
        assertThat(resolved).containsExactly(hosted)
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

    @Test
    fun `balance authorization requires ownership and the first committed proof claim`() {
        val grant = grant()
        val proofKey = "0".repeat(64)
        val ownership = mockk<ResourceOwnershipClient>()
        val replay = mockk<McpIssuerProofReplayStore>()
        val authorization = McpBalanceReadAuthorization(resolver, ownership, replay)
        val request = McpBalanceReadAuthorization.VerifiedRequest(party, clientId, jkt, grant.resourceId, proofKey)
        coEvery { repository.findActiveMcpClientGrants(party, clientId, jkt) } returns listOf(grant)
        coEvery { ownership.verifyOwnership(party, DelegationResourceType.ACCOUNT, grant.resourceId) } returns
            OwnershipVerdict.UNVERIFIABLE
        assertThat(runBlocking { authorization.authorize(request) }).isNull()
        coVerify(exactly = 0) { replay.claim(any()) }

        coEvery { ownership.verifyOwnership(party, DelegationResourceType.ACCOUNT, grant.resourceId) } returns
            OwnershipVerdict.OWNED
        coEvery { replay.claim(proofKey) } returnsMany listOf(
            ReplayClaim.FIRST_USE,
            ReplayClaim.REPLAYED,
            ReplayClaim.UNAVAILABLE,
        )
        val permit = runBlocking { authorization.authorize(request) }
        assertThat(permit).isEqualTo(
            McpBalanceReadAuthorization.Permit(grant.id, grant.resourceId, requireNotNull(grant.validTo)),
        )
        repeat(2) {
            assertThat(runBlocking { authorization.authorize(request) }).isNull()
        }
    }

    @Test
    fun `hosted balance permit requires a connection and the first issuer proof claim`() {
        val connection = UUID.randomUUID()
        val grant = grant().copy(mcpConnectionId = connection, granteeKeyThumbprint = null)
        val ownership = mockk<ResourceOwnershipClient>()
        val replay = mockk<McpIssuerProofReplayStore>()
        val authorization = McpBalanceReadAuthorization(resolver, ownership, replay)
        val request = McpBalanceReadAuthorization.VerifiedRequest(
            party,
            clientId,
            null,
            grant.resourceId,
            "0".repeat(64),
        )
        assertThat(runBlocking { authorization.authorize(request) }).isNull()

        coEvery { repository.findActiveHostedMcpClientGrants(party, clientId, connection) } returns listOf(grant)
        coEvery { ownership.verifyOwnership(party, DelegationResourceType.ACCOUNT, grant.resourceId) } returns
            OwnershipVerdict.OWNED
        coEvery { replay.claim(request.proofKey) } returnsMany listOf(ReplayClaim.FIRST_USE, ReplayClaim.REPLAYED)
        assertThat(runBlocking { authorization.authorize(request.copy(connectionId = connection)) }?.grantId)
            .isEqualTo(grant.id)
        assertThat(runBlocking { authorization.authorize(request.copy(connectionId = connection)) }).isNull()
    }

    @Test
    fun `balance grant recheck denies changed bindings and immediate revocation`() {
        val currentTime = OffsetDateTime.now()
        val grant = grant().copy(validFrom = currentTime.minusMinutes(1), validTo = currentTime.plusDays(1))
        val check = McpBalanceReadGrantCheck(repository)
        val binding = McpBalanceReadGrantCheck.Binding(grant.id, party, clientId, grant.resourceId)
        coEvery { repository.findById(grant.id) } returns grant
        assertThat(runBlocking { check.isActive(binding) }).isTrue()
        assertThat(runBlocking { check.isActive(binding.copy(accountId = UUID.randomUUID())) }).isFalse()
        assertThat(runBlocking { check.isActive(binding.copy(partyId = UUID.randomUUID())) }).isFalse()
        assertThat(runBlocking { check.isActive(binding.copy(clientId = "different-client")) }).isFalse()

        coEvery { repository.findById(grant.id) } returns grant.revoke(party, "customer revoked", currentTime)
        assertThat(runBlocking { check.isActive(binding) }).isFalse()
    }
}
