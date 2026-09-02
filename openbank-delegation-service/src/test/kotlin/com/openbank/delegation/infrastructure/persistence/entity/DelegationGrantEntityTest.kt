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
            lifecycleRevision = 7,
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
            // Scaled to the currency's minor unit, which is what the mapper now guarantees on the
            // way back: the columns are NUMERIC(20,6) and Postgres returns scale 6, so the mapper
            // re-scales or `Money` refuses the value outright. Writing the fixture at scale 0 hid
            // that, because `fromDomain`/`toDomain` in isolation never sees the database's scale.
            perTransactionLimit = Money.of("5000.00".toBigDecimal(), "CZK"),
            dailyLimit = Money.of("10000.00".toBigDecimal(), "CZK"),
            monthlyLimit = Money.of("50000.00".toBigDecimal(), "CZK"),
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

    /**
     * The database column is NUMERIC(20,6); `Money` refuses a scale wider than the currency has.
     * Read straight through, a stored CZK ceiling therefore threw "Amount scale 6 exceeds currency
     * CZK fraction digits 2" and took the whole read with it. Nothing caught it before ADR-0249
     * because #3613 refused the cumulative ceilings at the API, so no row carried one.
     */
    @Test
    fun `a ceiling stored at the column's scale rehydrates instead of throwing`() {
        val entity = DelegationGrantEntity().apply {
            id = UUID.randomUUID()
            grantorPartyId = UUID.randomUUID()
            granteePartyId = UUID.randomUUID()
            resourceType = DelegationResourceType.ACCOUNT
            resourceId = UUID.randomUUID()
            capabilities = mutableSetOf(DelegationCapability.ACCOUNT_INITIATE_PAYMENT)
            approvalPolicy = ApprovalPolicy.SOLO
            dailyLimitAmount = "5000.000000".toBigDecimal()
            dailyLimitCurrency = "CZK"
            validFrom = now
            status = DelegationStatus.ACTIVE
            createdAt = now
            updatedAt = now
        }

        assertThat(entity.toDomain().dailyLimit).isEqualTo(Money.of("5000.00".toBigDecimal(), "CZK"))
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
