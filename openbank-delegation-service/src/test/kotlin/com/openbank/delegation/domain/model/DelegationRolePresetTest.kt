// SPDX-License-Identifier: Apache-2.0
package com.openbank.delegation.domain.model

import org.assertj.core.api.Assertions.assertThatCode
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

    @Test
    fun `accepts expanded account and card capabilities in their resource matrix`() {
        assertThatCode {
            DelegationRolePreset(
                name = "Account owner",
                description = "",
                resourceType = DelegationResourceType.ACCOUNT,
                capabilities = setOf(
                    DelegationCapability.ACCOUNT_VIEW_DETAILS,
                    DelegationCapability.ACCOUNT_DOWNLOAD_STATEMENTS,
                    DelegationCapability.ACCOUNT_MANAGE_BENEFICIARIES,
                    DelegationCapability.ACCOUNT_MANAGE_LIMITS,
                ),
                createdAt = now,
                updatedAt = now,
            )
            DelegationRolePreset(
                name = "Card owner",
                description = "",
                resourceType = DelegationResourceType.CARD,
                capabilities = setOf(
                    DelegationCapability.CARD_VIEW_TRANSACTIONS,
                    DelegationCapability.CARD_MANAGE_STATUS,
                    DelegationCapability.CARD_MANAGE_CHANNELS,
                ),
                createdAt = now,
                updatedAt = now,
            )
        }.doesNotThrowAnyException()
    }
}
