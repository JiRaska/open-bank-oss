// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.rest.dto

import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationCheckResult
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.DelegationStatus
import com.openbank.delegation.domain.model.Exposure
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

class DelegationDtosTest {

    private val now: OffsetDateTime = OffsetDateTime.parse("2026-07-31T12:00:00Z")

    private fun grant() = DelegationGrant(
        grantorPartyId = UUID.randomUUID(),
        granteePartyId = UUID.randomUUID(),
        resourceType = DelegationResourceType.STATEMENT,
        resourceId = UUID.randomUUID(),
        capabilities = setOf(DelegationCapability.OBJECT_READ),
        exposure = Exposure(redactionRules = listOf("credits-only"), maxViews = 1),
        validFrom = now,
        validTo = now.plusDays(3),
        status = DelegationStatus.ACTIVE,
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun `DelegationResponse maps the aggregate including exposure`() {
        val response = DelegationResponse.from(grant())
        assertThat(response.resourceType).isEqualTo(DelegationResourceType.STATEMENT)
        assertThat(response.exposure?.maxViews).isEqualTo(1)
        assertThat(response.exposure?.redactionRules).containsExactly("credits-only")
        assertThat(response.status).isEqualTo(DelegationStatus.ACTIVE)
    }

    /**
     * Issue #3604. The whole fix is worthless if the snapshotted label stops at the aggregate —
     * the accept screen only ever sees `DelegationResponse`.
     */
    @Test
    fun `DelegationResponse carries the snapshotted counterparty names to the client`() {
        val named = grant().copy(grantorName = "Alice Testerova", granteeName = "Bob Zkousky")

        val response = DelegationResponse.from(named)

        assertThat(response.grantorName).isEqualTo("Alice Testerova")
        assertThat(response.granteeName).isEqualTo("Bob Zkousky")
    }

    /** A pre-#3604 grant carries no label, and the response must say so rather than invent one. */
    @Test
    fun `a grant with no snapshotted name responds with nulls`() {
        val response = DelegationResponse.from(grant())

        assertThat(response.grantorName).isNull()
        assertThat(response.granteeName).isNull()
    }

    @Test
    fun `ExposureDto round-trips through the domain`() {
        val dto = ExposureDto(
            redactionRules = listOf("hide-counterparty"),
            maxViews = 5,
            watermark = false,
            allowDownload = true,
        )
        val back = ExposureDto.from(dto.toDomain())
        assertThat(back).isEqualTo(dto)
    }

    @Test
    fun `check response leaks a decision, never the grant`() {
        val allowed = DelegationCheckResponse.from(DelegationCheckResult.Allowed(grant()))
        assertThat(allowed.granted).isTrue()
        assertThat(allowed.reason).isNull()

        val denied = DelegationCheckResponse.from(DelegationCheckResult.Denied("nope", "DELEGATION_NOT_COVERED"))
        assertThat(denied.granted).isFalse()
        assertThat(denied.code).isEqualTo("DELEGATION_NOT_COVERED")
    }

    @Test
    fun `MoneyDto round-trips`() {
        val dto = MoneyDto("1234.56".toBigDecimal(), "CZK")
        val back = MoneyDto.from(dto.toDomain())
        assertThat(back.amount.compareTo(dto.amount)).isEqualTo(0)
        assertThat(back.currency).isEqualTo("CZK")
    }
}
