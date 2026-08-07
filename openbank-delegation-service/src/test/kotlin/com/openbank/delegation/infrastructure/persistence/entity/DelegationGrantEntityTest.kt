// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.persistence.entity

import com.openbank.delegation.domain.model.ApprovalPolicy
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.DelegationStatus
import com.openbank.delegation.domain.model.Exposure
import com.openbank.libs.domain.money.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

class DelegationGrantEntityTest {

    private val now: OffsetDateTime = OffsetDateTime.parse("2026-07-31T12:00:00Z")

    @Test
    fun `round-trip preserves every field`() {
        val grant = DelegationGrant(
            id = UUID.randomUUID(),
            grantorPartyId = UUID.randomUUID(),
            granteePartyId = UUID.randomUUID(),
            // #3604 — the snapshotted counterparty labels are columns like any other, and this
            // assertion is `isEqualTo(grant)`, so a mapping that dropped them would fail here.
            grantorName = "Alice Testerova",
            granteeName = "Bob Zkousky",
            resourceType = DelegationResourceType.PAYMENT,
            resourceId = UUID.randomUUID(),
            capabilities = setOf(DelegationCapability.OBJECT_READ),
            approvalPolicy = ApprovalPolicy.SOLO,
            exposure = Exposure(
                redactionRules = listOf("hide-counterparty", "credits-only"),
                maxViews = 3,
                watermark = true,
                allowDownload = false,
            ),
            validFrom = now,
            validTo = now.plusDays(7),
            status = DelegationStatus.ACTIVE,
            grantScaSessionId = UUID.randomUUID(),
            acceptScaSessionId = UUID.randomUUID(),
            note = "proof for buyer",
            createdAt = now,
            updatedAt = now,
        )

        val restored = DelegationGrantEntity.fromDomain(grant).toDomain()

        assertThat(restored).isEqualTo(grant)
    }

    @Test
    fun `round-trip preserves limits and closed state`() {
        val grant = DelegationGrant(
            grantorPartyId = UUID.randomUUID(),
            granteePartyId = UUID.randomUUID(),
            resourceType = DelegationResourceType.ACCOUNT,
            resourceId = UUID.randomUUID(),
            capabilities = setOf(DelegationCapability.ACCOUNT_INITIATE_PAYMENT),
            approvalPolicy = ApprovalPolicy.N_OF_M,
            requiredApprovals = 2,
            perTransactionLimit = Money.of("5000".toBigDecimal(), "CZK"),
            dailyLimit = Money.of("10000".toBigDecimal(), "CZK"),
            monthlyLimit = Money.of("50000".toBigDecimal(), "CZK"),
            validFrom = now,
            validTo = null,
            status = DelegationStatus.REVOKED,
            createdAt = now,
            updatedAt = now,
            closedAt = now.plusDays(1),
            closedBy = UUID.randomUUID(),
            closedReason = "offboarded",
        )

        val restored = DelegationGrantEntity.fromDomain(grant).toDomain()

        assertThat(restored).isEqualTo(grant)
        assertThat(restored.perTransactionLimit?.currency?.code).isEqualTo("CZK")
    }

    @Test
    fun `empty exposure columns map back to null exposure`() {
        val grant = DelegationGrant(
            grantorPartyId = UUID.randomUUID(),
            granteePartyId = UUID.randomUUID(),
            resourceType = DelegationResourceType.ACCOUNT,
            resourceId = UUID.randomUUID(),
            capabilities = setOf(DelegationCapability.ACCOUNT_READ_BALANCES),
            validFrom = now,
            validTo = null,
            createdAt = now,
            updatedAt = now,
        )

        assertThat(DelegationGrantEntity.fromDomain(grant).toDomain().exposure).isNull()
    }
}
