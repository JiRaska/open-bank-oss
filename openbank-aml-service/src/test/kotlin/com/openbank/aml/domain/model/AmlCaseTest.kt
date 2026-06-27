// SPDX-License-Identifier: Apache-2.0\n// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.\n// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.\n
package com.openbank.aml.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class AmlCaseTest {

    @Test
    fun `blocking requires decision reason`() {
        assertThatThrownBy {
            amlCase().transitionTo(AmlCaseStatus.BLOCKED, null, null, "analyst", Instant.parse("2026-01-02T00:00:00Z"))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("decisionReason is required")
    }

    @Test
    fun `transition trims analyst and decidedBy metadata`() {
        val updated = amlCase(status = AmlCaseStatus.OPEN).transitionTo(
            targetStatus = AmlCaseStatus.UNDER_REVIEW,
            decisionReason = "  review  ",
            assignedAnalyst = "  alice  ",
            decidedBy = "  bob  ",
            now = Instant.parse("2026-01-02T00:00:00Z"),
        )

        assertThat(updated.status).isEqualTo(AmlCaseStatus.UNDER_REVIEW)
        assertThat(updated.assignedAnalyst).isEqualTo("alice")
        assertThat(updated.decidedBy).isEqualTo("bob")
    }

    private fun amlCase(status: AmlCaseStatus = AmlCaseStatus.OPEN) = AmlCase(
        id = UUID.randomUUID(),
        idempotencyKey = "idem",
        partyId = UUID.randomUUID(),
        accountId = null,
        transactionId = null,
        customerReference = "CUST-1",
        screeningType = ScreeningType.CUSTOMER_ONBOARDING,
        riskLevel = AmlRiskLevel.MEDIUM,
        status = status,
        alertCode = "ALERT-1",
        alertDetail = null,
        matchedEntity = null,
        decisionReason = null,
        assignedAnalyst = null,
        decidedBy = null,
        screenedAt = Instant.parse("2026-01-01T00:00:00Z"),
        decidedAt = null,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )
}
