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

class DelegationGrantTest {

    private val now: OffsetDateTime = OffsetDateTime.parse("2026-07-31T12:00:00Z")
    private val grantor: UUID = UUID.randomUUID()
    private val grantee: UUID = UUID.randomUUID()
    private val accountId: UUID = UUID.randomUUID()

    private fun accountGrant(
        capabilities: Set<DelegationCapability> = setOf(DelegationCapability.ACCOUNT_READ_BALANCES),
        validTo: OffsetDateTime? = now.plusDays(30),
        status: DelegationStatus = DelegationStatus.OFFERED,
    ) = DelegationGrant(
        grantorPartyId = grantor,
        granteePartyId = grantee,
        resourceType = DelegationResourceType.ACCOUNT,
        resourceId = accountId,
        capabilities = capabilities,
        validFrom = now,
        validTo = validTo,
        status = status,
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun `rejects grantor equal to grantee`() {
        assertThatThrownBy {
            DelegationGrant(
                grantorPartyId = grantor,
                granteePartyId = grantor,
                resourceType = DelegationResourceType.ACCOUNT,
                resourceId = accountId,
                capabilities = setOf(DelegationCapability.ACCOUNT_READ_BALANCES),
                validFrom = now,
                validTo = null,
                createdAt = now,
                updatedAt = now,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects capability outside the resource matrix`() {
        assertThatThrownBy {
            accountGrant(capabilities = setOf(DelegationCapability.CARD_VIEW))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("not valid for resource type")
    }

    @Test
    fun `rejects exposure on a product-level grant`() {
        assertThatThrownBy {
            DelegationGrant(
                grantorPartyId = grantor,
                granteePartyId = grantee,
                resourceType = DelegationResourceType.ACCOUNT,
                resourceId = accountId,
                capabilities = setOf(DelegationCapability.ACCOUNT_READ_BALANCES),
                exposure = Exposure(),
                validFrom = now,
                validTo = null,
                createdAt = now,
                updatedAt = now,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects execution capability on an object-level grant`() {
        assertThatThrownBy {
            DelegationGrant(
                grantorPartyId = grantor,
                granteePartyId = grantee,
                resourceType = DelegationResourceType.PAYMENT,
                resourceId = UUID.randomUUID(),
                capabilities = setOf(DelegationCapability.ACCOUNT_INITIATE_PAYMENT),
                validFrom = now,
                validTo = null,
                createdAt = now,
                updatedAt = now,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects N_OF_M without requiredApprovals`() {
        assertThatThrownBy {
            DelegationGrant(
                grantorPartyId = grantor,
                granteePartyId = grantee,
                resourceType = DelegationResourceType.ACCOUNT,
                resourceId = accountId,
                capabilities = setOf(DelegationCapability.ACCOUNT_READ_BALANCES),
                approvalPolicy = ApprovalPolicy.N_OF_M,
                validFrom = now,
                validTo = null,
                createdAt = now,
                updatedAt = now,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `accept activates an offered grant and stamps the SCA session`() {
        val sca = UUID.randomUUID()
        val accepted = accountGrant().accept(sca, now.plusHours(1))
        assertThat(accepted.status).isEqualTo(DelegationStatus.ACTIVE)
        assertThat(accepted.acceptScaSessionId).isEqualTo(sca)
    }

    @Test
    fun `accept on an active grant fails closed`() {
        val active = accountGrant(status = DelegationStatus.ACTIVE)
        assertThatThrownBy { active.accept(UUID.randomUUID(), now) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `revoke closes an active grant with actor and reason`() {
        val active = accountGrant(status = DelegationStatus.ACTIVE)
        val revoked = active.revoke(grantor, "no longer needed", now.plusDays(1))
        assertThat(revoked.status).isEqualTo(DelegationStatus.REVOKED)
        assertThat(revoked.closedBy).isEqualTo(grantor)
        assertThat(revoked.closedReason).isEqualTo("no longer needed")
        assertThat(revoked.closedAt).isEqualTo(now.plusDays(1))
    }

    @Test
    fun `renounce only from active or suspended`() {
        val offered = accountGrant()
        assertThatThrownBy { offered.renounce(now) }.isInstanceOf(IllegalStateException::class.java)
        val active = accountGrant(status = DelegationStatus.ACTIVE)
        assertThat(active.renounce(now).status).isEqualTo(DelegationStatus.RENOUNCED)
    }

    @Test
    fun `suspend and reinstate round-trip`() {
        val active = accountGrant(status = DelegationStatus.ACTIVE)
        val suspended = active.suspend("fraud signal", now)
        assertThat(suspended.status).isEqualTo(DelegationStatus.SUSPENDED)
        assertThat(suspended.reinstate(now).status).isEqualTo(DelegationStatus.ACTIVE)
    }

    @Test
    fun `isActiveOn respects window and status`() {
        val active = accountGrant(status = DelegationStatus.ACTIVE)
        assertThat(active.isActiveOn(now.plusDays(1))).isTrue()
        assertThat(active.isActiveOn(now.plusDays(31))).isFalse()
        assertThat(accountGrant().isActiveOn(now.plusDays(1))).isFalse()
    }

    @Test
    fun `covers enforces per-transaction ceiling`() {
        val grant = DelegationGrant(
            grantorPartyId = grantor,
            granteePartyId = grantee,
            resourceType = DelegationResourceType.ACCOUNT,
            resourceId = accountId,
            capabilities = setOf(DelegationCapability.ACCOUNT_INITIATE_PAYMENT),
            perTransactionLimit = Money.of("5000".toBigDecimal(), "CZK"),
            validFrom = now,
            validTo = null,
            status = DelegationStatus.ACTIVE,
            createdAt = now,
            updatedAt = now,
        )
        assertThat(
            grant.covers(DelegationCapability.ACCOUNT_INITIATE_PAYMENT, Money.of("4999".toBigDecimal(), "CZK")),
        ).isTrue()
        assertThat(
            grant.covers(DelegationCapability.ACCOUNT_INITIATE_PAYMENT, Money.of("5001".toBigDecimal(), "CZK")),
        ).isFalse()
        assertThat(grant.covers(DelegationCapability.ACCOUNT_READ_BALANCES, null)).isFalse()
    }
}
