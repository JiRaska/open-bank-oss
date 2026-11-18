// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.`in`.RevokeDelegationCommand
import com.openbank.delegation.application.port.`in`.SuspendDelegationCommand
import com.openbank.delegation.application.port.out.ApprovalGroupRepository
import com.openbank.delegation.application.port.out.DelegationRepository
import com.openbank.delegation.application.port.out.GrantorAuthorityClient
import com.openbank.delegation.application.port.out.PartyEligibilityClient
import com.openbank.delegation.application.port.out.ResourceOwnershipClient
import com.openbank.delegation.application.port.out.ScaChallengeClient
import com.openbank.delegation.domain.event.DelegationRevoked
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationGranteeType
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.DelegationStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * ADR-0383 D3 dormancy guard. Until the delegation event consumers skip `granteeType != PARTY`, no
 * write path may persist or publish an MCP_CLIENT grant. Each test here goes red the day someone
 * wires one through without lifting the guard deliberately.
 */
class DelegationServiceMcpClientDormancyTest {

    private val repository: DelegationRepository = mockk()
    private val scaClient: ScaChallengeClient = mockk()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-11-17T12:00:00Z"), ZoneOffset.UTC)
    private val now: OffsetDateTime = OffsetDateTime.now(clock)
    private val grantor: UUID = UUID.randomUUID()
    private val clientId = "registered-mcp-client"
    private val granteeId: UUID = DelegationGrant.mcpClientGranteeId(clientId)

    private val service = DelegationService(
        repository,
        scaClient,
        mockk<PartyEligibilityClient>(),
        mockk<GrantorAuthorityClient>(),
        mockk<ResourceOwnershipClient>(),
        mockk<ApprovalGroupRepository>(),
        true,
        clock,
    )

    private fun mcpGrant(status: DelegationStatus) = DelegationGrant(
        grantorPartyId = grantor,
        granteePartyId = granteeId,
        granteeType = DelegationGranteeType.MCP_CLIENT,
        granteeClientId = clientId,
        granteeKeyThumbprint = "NzbLsXh8uDCcd-6MNwXF4W_7noWXFZAfHkxZsRGC9Xs",
        resourceType = DelegationResourceType.ACCOUNT,
        resourceId = UUID.randomUUID(),
        capabilities = setOf(DelegationCapability.ACCOUNT_READ_BALANCES),
        validFrom = now,
        validTo = now.plusDays(30),
        status = status,
        createdAt = now,
        updatedAt = now,
    ).also { coEvery { repository.findById(it.id) } returns it }

    private fun assertDormant(block: suspend () -> Unit) {
        assertThatThrownBy { runBlocking { block() } }
            .isInstanceOf(DelegationUnsupportedConstraintException::class.java)
            .extracting("code")
            .isEqualTo(DelegationUnsupportedConstraintException.CODE_MCP_CLIENT_GRANTEE_DORMANT)
        coVerify(exactly = 0) { repository.save(any(), any()) }
    }

    @Test
    fun `revoke refuses an MCP_CLIENT grant and writes nothing`() {
        val g = mcpGrant(DelegationStatus.ACTIVE)
        assertDormant { service.revoke(RevokeDelegationCommand(g.id, grantor, "stop")) }
    }

    @Test
    fun `suspend refuses an MCP_CLIENT grant and writes nothing`() {
        val g = mcpGrant(DelegationStatus.ACTIVE)
        assertDormant { service.suspend(SuspendDelegationCommand(g.id, "review")) }
    }

    @Test
    fun `reinstate refuses an MCP_CLIENT grant and writes nothing`() {
        val g = mcpGrant(DelegationStatus.SUSPENDED)
        assertDormant { service.reinstate(g.id) }
    }

    @Test
    fun `decline and renounce refuse an MCP_CLIENT grant and write nothing`() {
        val offered = mcpGrant(DelegationStatus.OFFERED)
        assertDormant { service.decline(offered.id, granteeId, granteeId) }
        val active = mcpGrant(DelegationStatus.ACTIVE)
        assertDormant { service.renounce(active.id, granteeId, granteeId) }
    }

    @Test
    fun `accept refuses an MCP_CLIENT grant before spending any SCA challenge`() {
        val g = mcpGrant(DelegationStatus.OFFERED)
        assertDormant { service.accept(g.id, granteeId, UUID.randomUUID(), granteeId) }
        coVerify(exactly = 0) { scaClient.getChallenge(any()) }
        coVerify(exactly = 0) { scaClient.consumeChallenge(any(), any(), any()) }
    }

    @Test
    fun `no lifecycle event can be constructed for an MCP_CLIENT grant (backstop for every producer)`() {
        assertThatThrownBy {
            DelegationRevoked(
                aggregateId = UUID.randomUUID(),
                lifecycleRevision = 1,
                grantorPartyId = grantor,
                granteePartyId = granteeId,
                granteeType = DelegationGranteeType.MCP_CLIENT,
                granteeClientId = clientId,
                resourceType = DelegationResourceType.ACCOUNT,
                resourceId = UUID.randomUUID(),
                capabilities = setOf(DelegationCapability.ACCOUNT_READ_BALANCES),
                reason = "x",
                occurredAt = clock.instant(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("dormant")
    }

    @Test
    fun `a PARTY event defaults the new optional fields`() {
        val e = DelegationRevoked(
            aggregateId = UUID.randomUUID(),
            lifecycleRevision = 1,
            grantorPartyId = grantor,
            granteePartyId = UUID.randomUUID(),
            resourceType = DelegationResourceType.ACCOUNT,
            resourceId = UUID.randomUUID(),
            capabilities = setOf(DelegationCapability.ACCOUNT_READ_BALANCES),
            reason = "x",
            occurredAt = clock.instant(),
        )
        assertThat(e.granteeType).isEqualTo(DelegationGranteeType.PARTY)
        assertThat(e.granteeClientId).isNull()
    }
}
