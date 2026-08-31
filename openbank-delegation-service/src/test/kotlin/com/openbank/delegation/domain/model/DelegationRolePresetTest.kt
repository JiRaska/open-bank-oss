// SPDX-License-Identifier: Apache-2.0
package com.openbank.delegation.domain.model

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class DelegationRolePresetTest {
    private val now = OffsetDateTime.parse("2026-08-31T12:00:00Z")

    @Test
    fun `rejects capability outside resource matrix`() {
        assertThatThrownBy {
            DelegationRolePreset(
                name = "Broken",
                description = "",
                resourceType = DelegationResourceType.CARD,
                capabilities = setOf(DelegationCapability.ACCOUNT_INITIATE_PAYMENT),
                createdAt = now,
                updatedAt = now,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
