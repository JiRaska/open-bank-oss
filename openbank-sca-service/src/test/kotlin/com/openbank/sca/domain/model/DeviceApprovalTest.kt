// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.sca.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

class DeviceApprovalTest {

    private val now = OffsetDateTime.now()

    @Test
    fun `payload binds the decision so APPROVED cannot be replayed as DENIED`() {
        val challenge = paymentChallenge()
        assertThat(challenge.dynamicLinkingPayload(DeviceDecisionType.APPROVED))
            .isNotEqualTo(challenge.dynamicLinkingPayload(DeviceDecisionType.DENIED))
    }

    @Test
    fun `payload binds amount and payee so a different amount yields a different payload`() {
        val base = paymentChallenge(amount = "100.00")
        val higher = paymentChallenge(amount = "999.00", id = base.id)

        assertThat(base.dynamicLinkingPayload(DeviceDecisionType.APPROVED))
            .isNotEqualTo(higher.dynamicLinkingPayload(DeviceDecisionType.APPROVED))
    }

    @Test
    fun `payload is stable for a challenge with no payment context`() {
        val login = ScaChallenge(
            partyId = UUID.randomUUID(),
            purpose = ScaPurpose.LOGIN,
            method = ScaMethod.PUSH_NOTIFICATION,
            expiresAt = now.plusMinutes(5),
            dynamicLinkingData = null,
            createdAt = now,
        )
        val payload = String(login.dynamicLinkingPayload(DeviceDecisionType.APPROVED))
        assertThat(payload).isEqualTo("${login.id}|APPROVED||||")
    }

    private fun paymentChallenge(id: UUID = UUID.randomUUID(), amount: String = "100.00") = ScaChallenge(
        id = id,
        partyId = UUID.randomUUID(),
        purpose = ScaPurpose.PAYMENT_INITIATION,
        method = ScaMethod.PUSH_NOTIFICATION,
        expiresAt = now.plusMinutes(5),
        dynamicLinkingData = DynamicLinkingData(
            amount = amount,
            currency = "EUR",
            creditorIban = "CZ6508000000192000145399",
            creditorName = "Acme",
            reference = "INV-1",
        ),
        createdAt = now,
    )
}
