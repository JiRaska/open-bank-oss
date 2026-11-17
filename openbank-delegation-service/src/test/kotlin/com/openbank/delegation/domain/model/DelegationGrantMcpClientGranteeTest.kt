// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.domain.model

import com.openbank.libs.domain.money.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

/** ADR-0383 D3 — the MCP_CLIENT grantee class may only read and propose. */
class DelegationGrantMcpClientGranteeTest {

    private val now: OffsetDateTime = OffsetDateTime.parse("2026-11-17T12:00:00Z")
    private val grantor: UUID = UUID.randomUUID()
    private val clientId = "registered-mcp-client"
    private val jkt = "NzbLsXh8uDCcd-6MNwXF4W_7noWXFZAfHkxZsRGC9Xs"

    private fun mcpGrant(
        capabilities: Set<DelegationCapability>,
        resourceType: DelegationResourceType = DelegationResourceType.ACCOUNT,
        granteeClientId: String? = clientId,
        granteeKeyThumbprint: String? = jkt,
        granteePartyId: UUID = DelegationGrant.mcpClientGranteeId(clientId),
        perTransactionLimit: Money? = null,
    ) = DelegationGrant(
        grantorPartyId = grantor,
        granteePartyId = granteePartyId,
        granteeType = DelegationGranteeType.MCP_CLIENT,
        granteeClientId = granteeClientId,
        granteeKeyThumbprint = granteeKeyThumbprint,
        resourceType = resourceType,
        resourceId = UUID.randomUUID(),
        capabilities = capabilities,
        perTransactionLimit = perTransactionLimit,
        validFrom = now,
        validTo = now.plusDays(90),
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun `accepts reads plus propose-payment and records the client and key binding`() {
        val grant = mcpGrant(DelegationGrant.MCP_CLIENT_PERMITTED_CAPABILITIES)

        assertThat(grant.granteeType).isEqualTo(DelegationGranteeType.MCP_CLIENT)
        assertThat(grant.granteeClientId).isEqualTo(clientId)
        assertThat(grant.granteeKeyThumbprint).isEqualTo(jkt)
        assertThat(grant.hasCapability(DelegationCapability.ACCOUNT_INITIATE_PAYMENT)).isFalse()
    }

    /**
     * Every capability outside the allow-list, enumerated from the vocabulary itself — so a
     * capability added to [DelegationCapability] later is covered here automatically, and is
     * refused until the allow-list names it.
     */
    @Test
    fun `refuses every executing or managing capability`() {
        val refused = DelegationCapability.entries.filter { it !in DelegationGrant.MCP_CLIENT_PERMITTED_CAPABILITIES }
        assertThat(refused).contains(
            DelegationCapability.ACCOUNT_INITIATE_PAYMENT,
            DelegationCapability.DELEGATION_MANAGE,
            DelegationCapability.SAVINGS_WITHDRAW,
            DelegationCapability.CARD_MANAGE_STATUS,
        )
        refused.forEach { capability ->
            val resourceType = DelegationGrant.CAPABILITY_MATRIX.entries.first { capability in it.value }.key
            assertThatThrownBy { mcpGrant(setOf(capability), resourceType = resourceType) }
                .describedAs("$capability must be refused for an MCP_CLIENT grantee")
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("refused for an MCP_CLIENT grantee")
        }
    }

    @Test
    fun `a ceiling does not make initiate-payment acceptable`() {
        assertThatThrownBy {
            mcpGrant(
                setOf(DelegationCapability.ACCOUNT_READ_BALANCES, DelegationCapability.ACCOUNT_INITIATE_PAYMENT),
                perTransactionLimit = Money.of("1".toBigDecimal(), "CZK"),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("ACCOUNT_INITIATE_PAYMENT")
    }

    @Test
    fun `the allow-list holds no execution capability and no manage capability`() {
        val permitted = DelegationGrant.MCP_CLIENT_PERMITTED_CAPABILITIES
        assertThat(permitted).doesNotContainAnyElementsOf(DelegationGrant.EXECUTION_CAPABILITIES)
        assertThat(permitted.map { it.name }).noneMatch { it.contains("MANAGE") || it.contains("WITHDRAW") }
        assertThat(permitted).containsExactlyInAnyOrder(
            DelegationCapability.ACCOUNT_READ_BALANCES,
            DelegationCapability.ACCOUNT_READ_TRANSACTIONS,
            DelegationCapability.ACCOUNT_VIEW_DETAILS,
            DelegationCapability.ACCOUNT_DOWNLOAD_STATEMENTS,
            DelegationCapability.ACCOUNT_PROPOSE_PAYMENT,
        )
    }

    @Test
    fun `requires the client id and the DPoP key thumbprint`() {
        val caps = setOf(DelegationCapability.ACCOUNT_READ_BALANCES)
        assertThatThrownBy { mcpGrant(caps, granteeClientId = null) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("azp")
        assertThatThrownBy { mcpGrant(caps, granteeKeyThumbprint = null) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("cnf.jkt")
        assertThatThrownBy { mcpGrant(caps, granteeKeyThumbprint = "not-a-thumbprint") }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("thumbprint")
    }

    @Test
    fun `the grantee id must be derived from the client id`() {
        assertThatThrownBy {
            mcpGrant(setOf(DelegationCapability.ACCOUNT_READ_BALANCES), granteePartyId = UUID.randomUUID())
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("derived from its client id")
        assertThat(DelegationGrant.mcpClientGranteeId(clientId)).isEqualTo(DelegationGrant.mcpClientGranteeId(clientId))
        assertThat(
            DelegationGrant.mcpClientGranteeId(clientId),
        ).isNotEqualTo(DelegationGrant.mcpClientGranteeId("other"))
    }

    @Test
    fun `a PARTY grant is unchanged and may not carry client bindings`() {
        val party = DelegationGrant(
            grantorPartyId = grantor,
            granteePartyId = UUID.randomUUID(),
            resourceType = DelegationResourceType.ACCOUNT,
            resourceId = UUID.randomUUID(),
            capabilities = setOf(DelegationCapability.ACCOUNT_INITIATE_PAYMENT),
            validFrom = now,
            validTo = null,
            createdAt = now,
            updatedAt = now,
        )
        assertThat(party.granteeType).isEqualTo(DelegationGranteeType.PARTY)
        assertThatThrownBy { party.copy(granteeClientId = clientId) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("only recorded for MCP_CLIENT")
    }
}
