// SPDX-License-Identifier: Apache-2.0
package com.openbank.settlement.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class SettlementApprovalFingerprintTest {
    private val original = CreateSettlementRequest(
        idempotencyKey = "operator-transfer",
        payerAccountId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        payeeAccountId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
        amount = BigDecimal("40.00"),
        currency = "CZK",
    )

    @Test
    fun `every changed instruction invalidates the approval binding`() {
        val changed = listOf(
            original.copy(idempotencyKey = "other-transfer"),
            original.copy(payerAccountId = UUID.fromString("00000000-0000-0000-0000-000000000003")),
            original.copy(payeeAccountId = UUID.fromString("00000000-0000-0000-0000-000000000003")),
            original.copy(amount = BigDecimal("40.01")),
            original.copy(currency = "EUR"),
        )
        assertThat(changed.map { it.approvalFingerprint })
            .doesNotContain(original.approvalFingerprint).doesNotHaveDuplicates()
    }

    @Test
    fun `equivalent decimal representations bind to the same instruction`() {
        for (amount in listOf("40", "40.0000", "4E+1")) {
            assertThat(original.copy(amount = BigDecimal(amount)).approvalFingerprint)
                .isEqualTo(original.approvalFingerprint)
        }
    }

    @Test
    fun `fingerprint is internal and does not expand the API payload`() {
        val json = ObjectMapper().valueToTree<com.fasterxml.jackson.databind.JsonNode>(original)
        assertThat(json.fieldNames().asSequence().toList()).containsExactlyInAnyOrder(
            "idempotencyKey",
            "payerAccountId",
            "payeeAccountId",
            "amount",
            "currency",
        )
        assertThat(original.approvalFingerprint).matches("[0-9a-f]{64}")
    }
}
