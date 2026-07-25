// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.infrastructure.rest

import com.openbank.sca.domain.model.DeviceDecisionType
import com.openbank.sca.domain.model.DynamicLinkingData
import com.openbank.sca.domain.model.EnrolledDevice
import com.openbank.sca.domain.model.ScaChallenge
import com.openbank.sca.domain.model.ScaMethod
import com.openbank.sca.domain.model.ScaPurpose
import com.openbank.sca.domain.model.ScaStatus
import com.openbank.sca.domain.model.SignatureAlgorithm
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The REST response DTOs are the wire contract the app and PSD2 TPPs read. Their `from()` mappers
 * were uncovered, so a field silently dropped or mis-mapped (e.g. the pending-approval projection
 * losing the creditor the customer must see before signing — RTS Art. 5 dynamic linking) would not
 * fail any test. These are pure mapping assertions: no framework, no I/O.
 */
class ScaResponseMapperTest {

    private val now: OffsetDateTime = OffsetDateTime.parse("2026-07-25T10:15:30Z")

    @Test
    fun `EnrolledDeviceResponse maps every field and stringifies the timestamp`() {
        val device = EnrolledDevice(
            id = UUID.randomUUID(),
            partyId = UUID.randomUUID(),
            credentialId = "cred-abc",
            publicKeySpkiB64 = "spki-b64",
            algorithm = SignatureAlgorithm.ES256,
            createdAt = now,
        )

        val dto = EnrolledDeviceResponse.from(device)

        assertThat(dto.id).isEqualTo(device.id)
        assertThat(dto.partyId).isEqualTo(device.partyId)
        assertThat(dto.credentialId).isEqualTo("cred-abc")
        assertThat(dto.algorithm).isEqualTo(SignatureAlgorithm.ES256)
        assertThat(dto.enrolledAt).isEqualTo(now.toString())
    }

    @Test
    fun `ScaChallengeResponse maps status fields and null-safe timestamps`() {
        val completed = now.plusMinutes(1)
        val consumed = now.plusMinutes(2)
        val challenge = ScaChallenge(
            partyId = UUID.randomUUID(),
            purpose = ScaPurpose.PAYMENT_INITIATION,
            method = ScaMethod.BIOMETRIC,
            status = ScaStatus.COMPLETED,
            expiresAt = now.plusMinutes(5),
            completedAt = completed,
            consumedAt = consumed,
            attemptCount = 2,
            maxAttempts = 3,
            createdAt = now,
        )

        val dto = ScaChallengeResponse.from(challenge)

        assertThat(dto.id).isEqualTo(challenge.id)
        assertThat(dto.status).isEqualTo(ScaStatus.COMPLETED)
        assertThat(dto.expiresAt).isEqualTo(challenge.expiresAt.toString())
        assertThat(dto.completedAt).isEqualTo(completed.toString())
        assertThat(dto.consumedAt).isEqualTo(consumed.toString())
        assertThat(dto.attemptCount).isEqualTo(2)
        assertThat(dto.maxAttempts).isEqualTo(3)
    }

    @Test
    fun `ScaChallengeResponse leaves optional timestamps null when the challenge is still pending`() {
        val challenge = ScaChallenge(
            partyId = UUID.randomUUID(),
            purpose = ScaPurpose.LOGIN,
            method = ScaMethod.TOTP,
            expiresAt = now.plusMinutes(5),
            createdAt = now,
        )

        val dto = ScaChallengeResponse.from(challenge)

        assertThat(dto.completedAt).isNull()
        assertThat(dto.consumedAt).isNull()
        assertThat(dto.status).isEqualTo(ScaStatus.PENDING)
    }

    @Test
    fun `PendingScaResponse surfaces the dynamic-linking data the customer approves`() {
        val challenge = ScaChallenge(
            partyId = UUID.randomUUID(),
            purpose = ScaPurpose.PAYMENT_INITIATION,
            method = ScaMethod.PUSH_NOTIFICATION,
            expiresAt = now.plusMinutes(5),
            dynamicLinkingData = DynamicLinkingData(
                amount = "250.00",
                currency = "EUR",
                creditorIban = "CZ6508000000192000145399",
                creditorName = "ACME s.r.o.",
                reference = "invoice-42",
            ),
            createdAt = now,
        )

        val dto = PendingScaResponse.from(challenge)

        assertThat(dto.amount).isEqualTo("250.00")
        assertThat(dto.currency).isEqualTo("EUR")
        assertThat(dto.creditorIban).isEqualTo("CZ6508000000192000145399")
        assertThat(dto.creditorName).isEqualTo("ACME s.r.o.")
        assertThat(dto.reference).isEqualTo("invoice-42")
        assertThat(dto.createdAt).isEqualTo(now.toString())
    }

    @Test
    fun `PendingScaResponse null-safely projects a challenge with no dynamic-linking data`() {
        val challenge = ScaChallenge(
            partyId = UUID.randomUUID(),
            purpose = ScaPurpose.LOGIN,
            method = ScaMethod.PUSH_NOTIFICATION,
            expiresAt = now.plusMinutes(5),
            dynamicLinkingData = null,
            createdAt = now,
        )

        val dto = PendingScaResponse.from(challenge)

        assertThat(dto.amount).isNull()
        assertThat(dto.currency).isNull()
        assertThat(dto.creditorIban).isNull()
        assertThat(dto.creditorName).isNull()
        assertThat(dto.reference).isNull()
    }

    @Test
    fun `request DTOs carry their fields through the constructor`() {
        val partyId = UUID.randomUUID()
        val initiate = InitiateScaRequest(
            partyId = partyId,
            purpose = ScaPurpose.PAYMENT_INITIATION,
            preferredMethod = ScaMethod.BIOMETRIC,
            dynamicLinkingData = null,
            redirectUrl = "https://app.example/return",
        )
        val verify = VerifyScaRequest(partyId = partyId, otp = "123456")
        val enroll = EnrollDeviceRequest(
            credentialId = "cred-1",
            publicKey = "spki",
            algorithm = SignatureAlgorithm.ED25519,
        )
        val decision = RecordDecisionRequest(
            credentialId = "cred-1",
            decision = DeviceDecisionType.APPROVED,
            signature = "sig",
        )
        val consume = ConsumeScaRequest(
            partyId = partyId,
            amount = "10.00",
            currency = "CZK",
            creditor = "192000145399/0800",
        )

        assertThat(initiate.partyId).isEqualTo(partyId)
        assertThat(initiate.redirectUrl).isEqualTo("https://app.example/return")
        assertThat(verify.otp).isEqualTo("123456")
        assertThat(enroll.algorithm).isEqualTo(SignatureAlgorithm.ED25519)
        assertThat(decision.decision).isEqualTo(DeviceDecisionType.APPROVED)
        assertThat(consume.creditor).isEqualTo("192000145399/0800")
        assertThat(consume.documentSha256).isNull()
    }
}
