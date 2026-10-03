// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.infrastructure.approval

import com.openbank.sca.application.port.out.EnrolledDeviceRepository
import com.openbank.sca.application.port.out.ScaChallengeRepository
import com.openbank.sca.domain.model.EnrolledDevice
import com.openbank.sca.domain.model.ScaChallenge
import com.openbank.sca.domain.model.ScaMethod
import com.openbank.sca.domain.model.ScaPurpose
import com.openbank.sca.domain.model.SignatureAlgorithm
import com.openbank.sca.infrastructure.rest.ConsumeScaRequest
import com.openbank.sca.infrastructure.rest.EnrollDeviceRequest
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.time.OffsetDateTime
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

class ScaApprovalSummariesTest {

    private val party = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val iban = "CZ65 0800 0000 1920 0014 5399"
    private val devices = mockk<EnrolledDeviceRepository>()
    private val challenges = mockk<ScaChallengeRepository>()
    private val renderer = ScaApprovalSummaryRenderer(devices, challenges)

    @Test
    fun `enroll summary names the key by a short fingerprint and never carries key material`() {
        val keyBytes = newKey()
        val key = Base64.getEncoder().encodeToString(keyBytes)
        val request = EnrollDeviceRequest("webauthn-credential-0123456789", key, SignatureAlgorithm.ES256)

        val summary = render("device.enroll", mapOf("partyId" to party, "request" to request))!!

        val expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(keyBytes)).take(8)
        assertThat(summary).isEqualTo(
            "action=device.enroll party=$party credential=webauthn… algorithm=ES256 keySha256=$expected",
        )
        assertThat(summary).doesNotContain(key).doesNotContain(key.take(16)).doesNotContain(key.takeLast(16))
    }

    @Test
    fun `an undecodable key is reported as such rather than echoed`() {
        assertThat(ScaApprovalSummaries.keyFingerprint("not base64 !!")).isEqualTo("undecodable")
    }

    @Test
    fun `revoke summary identifies the device the party holds`() {
        val device = EnrolledDevice(
            id = UUID.fromString("abcdef01-0000-0000-0000-000000000001"),
            partyId = party,
            credentialId = "cred-7f3a9c21d4",
            publicKeySpkiB64 = "SECRET-LOOKING-KEY",
            algorithm = SignatureAlgorithm.ED25519,
            createdAt = OffsetDateTime.parse("2026-09-01T10:00:00Z"),
        )
        coEvery { devices.findByPartyId(party) } returns listOf(device)

        val summary = render("device.revoke", mapOf("partyId" to party, "deviceId" to device.id))!!

        assertThat(summary).isEqualTo(
            "action=device.revoke party=$party device=abcdef01… credential=cred-7f3… algorithm=ED25519 " +
                "enrolledAt=2026-09-01",
        )
        assertThat(summary).doesNotContain("SECRET-LOOKING-KEY")
    }

    @Test
    fun `revoke of a device the party does not hold says so`() {
        coEvery { devices.findByPartyId(party) } returns emptyList()
        val summary = render("device.revoke", mapOf("partyId" to party, "deviceId" to UUID.randomUUID()))!!
        assertThat(summary).endsWith("target=not-a-device-of-this-party")
    }

    @Test
    fun `consume summary shows purpose amount and a creditor masked to its last four`() {
        val challengeId = UUID.fromString("99999999-9999-9999-9999-999999999999")
        coEvery { challenges.findById(challengeId) } returns challenge(challengeId, party)
        val request = ConsumeScaRequest(partyId = party, amount = "1250.50", currency = "CZK", creditor = iban)

        val summary = render("scaChallenge.consume", mapOf("id" to challengeId, "request" to request))!!

        assertThat(summary).contains("purpose=PAYMENT_INITIATION").contains("amount=1250.50 CZK")
            .contains("creditor=…5399")
        assertIbanMasked(summary)
    }

    @Test
    fun `a foreign party's challenge purpose is not disclosed`() {
        val challengeId = UUID.randomUUID()
        coEvery { challenges.findById(challengeId) } returns challenge(challengeId, UUID.randomUUID())
        val summary = render(
            "scaChallenge.consume",
            mapOf("id" to challengeId, "request" to ConsumeScaRequest(partyId = party)),
        )!!
        assertThat(summary).contains("purpose=unknown-challenge").doesNotContain("PAYMENT_INITIATION")
    }

    @Test
    fun `caller supplied fields that do not have their expected shape are not echoed`() {
        val summary = ScaApprovalSummaries.consume(
            UUID.randomUUID(),
            party,
            null,
            "1 approve=yes",
            "EURO",
            "x",
            "LIMIT increase",
            "abc",
        )
        assertThat(summary).contains("amount=invalid invalid").contains("creditor=****")
            .contains("cardAction=invalid").contains("documentSha256=invalid").doesNotContain("approve=yes")
    }

    @Test
    fun `masking keeps only the last four alphanumerics whatever the spacing`() {
        assertThat(ScaApprovalSummaries.mask(iban)).isEqualTo("…5399")
        assertThat(ScaApprovalSummaries.mask("cz6508000000192000145399")).isEqualTo("…5399")
        assertThat(ScaApprovalSummaries.mask("123")).isEqualTo("****")
    }

    @Test
    fun `actions the renderer does not cover keep the generic summary`() {
        assertThat(render("scaChallenge.decide", emptyMap())).isNull()
    }

    private fun assertIbanMasked(summary: String) {
        val compact = iban.replace(" ", "")
        assertThat(summary).doesNotContain(iban).doesNotContain(compact).doesNotContain(compact.dropLast(4))
            .doesNotContain("0800").doesNotContain("CZ65")
    }

    private fun render(action: String, args: Map<String, Any?>) = runBlocking { renderer.render(action, null, args) }

    private fun challenge(id: UUID, owner: UUID) = ScaChallenge(
        id = id,
        partyId = owner,
        purpose = ScaPurpose.PAYMENT_INITIATION,
        method = ScaMethod.PUSH_NOTIFICATION,
        expiresAt = OffsetDateTime.now().plusMinutes(5),
        createdAt = OffsetDateTime.now(),
    )

    private fun newKey(): ByteArray =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair().public.encoded
}
