// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

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

    // ADR-0169 D2: the document-signing segments are appended ONLY when present, so the payload
    // format for every pre-existing purpose (payment/login/consent/...) is byte-identical to
    // before this change shipped — a live app build signing the old 6-field payload must keep
    // verifying successfully.
    @Test
    fun `payload for a payment challenge is unchanged — no trailing document segments`() {
        val payment = paymentChallenge(amount = "100.00")
        val payload = String(payment.dynamicLinkingPayload(DeviceDecisionType.APPROVED))
        assertThat(payload).isEqualTo("${payment.id}|APPROVED|100.00|EUR|CZ6508000000192000145399|INV-1")
        assertThat(payload.split("|")).hasSize(6)
    }

    @Test
    fun `payload for a document-signing challenge appends the hash and ceremony id`() {
        val challenge = documentChallenge(sha256 = "abc123", ceremonyId = "ceremony-1")
        val payload = String(challenge.dynamicLinkingPayload(DeviceDecisionType.APPROVED))
        assertThat(payload).isEqualTo("${challenge.id}|APPROVED|||||abc123|ceremony-1")
    }

    @Test
    fun `payload binds the document hash so a different document yields a different payload`() {
        val a = documentChallenge(sha256 = "abc123", ceremonyId = "ceremony-1")
        val b = documentChallenge(sha256 = "def456", ceremonyId = "ceremony-1", id = a.id)
        assertThat(a.dynamicLinkingPayload(DeviceDecisionType.APPROVED))
            .isNotEqualTo(b.dynamicLinkingPayload(DeviceDecisionType.APPROVED))
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

    private fun documentChallenge(sha256: String, ceremonyId: String, id: UUID = UUID.randomUUID()) = ScaChallenge(
        id = id,
        partyId = UUID.randomUUID(),
        purpose = ScaPurpose.DOCUMENT_SIGNING,
        method = ScaMethod.BIOMETRIC,
        expiresAt = now.plusMinutes(5),
        dynamicLinkingData = DynamicLinkingData(
            amount = null,
            currency = null,
            creditorIban = null,
            creditorName = null,
            reference = null,
            documentSha256 = sha256,
            ceremonyId = ceremonyId,
        ),
        createdAt = now,
    )
}
