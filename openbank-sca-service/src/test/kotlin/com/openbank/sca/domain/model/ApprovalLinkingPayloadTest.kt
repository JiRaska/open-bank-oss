// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Cross-check of the APPROVAL dynamic-linking bytes against LITERAL strings (#10281). The app builds
 * the same string in `approvalDynamicLink`; if either side changes its formatting, a literal here
 * stops matching — a round-trip through our own builder could never notice that.
 */
class ApprovalLinkingPayloadTest {

    private val id = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e")
    private val approval = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
    private val sha = "9F86D081884C7D659A2FEAA0C55AD015A3BF4F1B2B0B822CD15D6C15B0F00A08"

    private fun challenge(dl: DynamicLinkingData) = ScaChallenge(
        id = id,
        partyId = UUID.randomUUID(),
        purpose = ScaPurpose.APPROVAL,
        method = ScaMethod.BIOMETRIC,
        expiresAt = OffsetDateTime.now().plusMinutes(5),
        dynamicLinkingData = dl,
        createdAt = OffsetDateTime.now(),
    )

    private fun bytes(dl: DynamicLinkingData, decision: DeviceDecisionType = DeviceDecisionType.APPROVED) =
        String(challenge(dl).dynamicLinkingPayload(decision), Charsets.UTF_8)

    @Test
    fun `non-payment approval - id, decision, APPROVAL, approval id, lower-case hash`() {
        val dl = DynamicLinkingData(null, null, null, null, null, approvalRequestId = approval, payloadSha256 = sha)
        assertThat(bytes(dl)).isEqualTo(
            "0f8fad5b-d9cb-469f-a165-70867728950e|APPROVED|APPROVAL|7c9e6679-7425-40de-944b-e07fc1f90ae7|" +
                "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
        )
    }

    @Test
    fun `payment approval - canonical amount, upper-case currency, compact upper-case IBAN`() {
        val dl = DynamicLinkingData(
            amount = "50000",
            currency = "czk",
            creditorIban = "cz65 0800 0000 1920 0014 5399",
            creditorName = "Dodavatel s.r.o.",
            reference = "INV-7",
            approvalRequestId = approval,
            payloadSha256 = sha,
        )
        assertThat(bytes(dl, DeviceDecisionType.DENIED)).isEqualTo(
            "0f8fad5b-d9cb-469f-a165-70867728950e|DENIED|APPROVAL|7c9e6679-7425-40de-944b-e07fc1f90ae7|" +
                "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08|50000.00|CZK|CZ6508000000192000145399",
        )
    }

    @Test
    fun `amount canonical form - two fractional digits, dot, no grouping, over-precision refused`() {
        assertThat(canonicalAmount("1000")).isEqualTo("1000.00")
        assertThat(canonicalAmount("250.5")).isEqualTo("250.50")
        assertThat(canonicalAmount("0.10")).isEqualTo("0.10")
        assertThat(canonicalAmount("1E+3")).isEqualTo("1000.00")
        assertThatThrownBy { canonicalAmount("1.005") }.isInstanceOf(ArithmeticException::class.java)
    }

    @Test
    fun `other purposes keep their bytes - the approval form never leaks into a payment challenge`() {
        val payment = challenge(
            DynamicLinkingData("100.00", "CZK", "CZ65", "X", "R"),
        ).copy(purpose = ScaPurpose.PAYMENT_INITIATION)
        assertThat(String(payment.dynamicLinkingPayload(DeviceDecisionType.APPROVED), Charsets.UTF_8))
            .isEqualTo("0f8fad5b-d9cb-469f-a165-70867728950e|APPROVED|100.00|CZK|CZ65|R")
    }
}
