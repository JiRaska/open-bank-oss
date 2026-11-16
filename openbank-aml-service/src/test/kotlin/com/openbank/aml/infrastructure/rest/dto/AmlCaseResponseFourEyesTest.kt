// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.rest.dto

import com.openbank.aml.domain.model.AmlCase
import com.openbank.aml.domain.model.AmlCaseStatus
import com.openbank.aml.domain.model.AmlRiskLevel
import com.openbank.aml.domain.model.ScreeningType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The response tells a client which decision flow a case takes, so an officer is not offered a
 * direct close that `AmlCaseService.updateDecision` will refuse after the rationale is written.
 */
class AmlCaseResponseFourEyesTest {

    @Test
    fun `a pack-monitoring case is flagged as four-eyes`() {
        val case = amlCase(ScreeningType.TRANSACTION_MONITORING, "${AmlCase.PACK_MONITORING_KEY_PREFIX}abc")
        assertThat(case.isPackMonitoringCase).isTrue()
        assertThat(case.toResponse().requiresFourEyesDisposition).isTrue()
    }

    @Test
    fun `a rail-opened monitoring case and an onboarding case keep the single-analyst flow`() {
        val railOpened = amlCase(ScreeningType.TRANSACTION_MONITORING, "rail-key").toResponse()
        val onboarding = amlCase(ScreeningType.CUSTOMER_ONBOARDING, "onboarding-1").toResponse()
        assertThat(railOpened.requiresFourEyesDisposition).isFalse()
        assertThat(onboarding.requiresFourEyesDisposition).isFalse()
    }

    @Test
    fun `a case with no established party says so instead of presenting something as its party`() {
        val account = UUID.randomUUID()
        val base = amlCase(ScreeningType.TRANSACTION_MONITORING, "${AmlCase.PAYMENT_HOLD_KEY_PREFIX}domestic:x")

        val missing = base.copy(partyId = null, accountId = account).toResponse()
        assertThat(missing.partyResolved).isFalse()
        assertThat(missing.partyId).isEqualTo(UUID(0L, 0L)).isNotEqualTo(account)
        assertThat(missing.accountId).isEqualTo(account)

        // A row from before a missing party could be stored: the account id sits where the party belongs.
        val accountAsParty = base.copy(partyId = account, accountId = account).toResponse()
        assertThat(accountAsParty.partyResolved).isFalse()

        val party = UUID.randomUUID()
        val resolved = base.copy(partyId = party, accountId = account).toResponse()
        assertThat(resolved.partyResolved).isTrue()
        assertThat(resolved.partyId).isEqualTo(party)
        assertThat(base.toResponse().partyResolved).describedAs("a party and no account").isTrue()
    }

    private fun amlCase(type: ScreeningType, key: String) = AmlCase(
        id = UUID.randomUUID(),
        idempotencyKey = key,
        partyId = UUID.randomUUID(),
        accountId = null,
        transactionId = null,
        customerReference = "CUST-1",
        screeningType = type,
        riskLevel = AmlRiskLevel.MEDIUM,
        status = AmlCaseStatus.OPEN,
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
