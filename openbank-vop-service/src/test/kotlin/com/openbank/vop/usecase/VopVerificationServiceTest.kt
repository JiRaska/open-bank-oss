// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.vop.usecase

import com.openbank.vop.application.port.`in`.VerifyPayeeCommand
import com.openbank.vop.application.port.out.AccountHolderNameLookupPort
import com.openbank.vop.application.port.out.NameLookupUnavailableException
import com.openbank.vop.application.port.out.VopSchemeRoutingPort
import com.openbank.vop.application.port.out.VopVerificationRecordPort
import com.openbank.vop.application.usecase.VopVerificationService
import com.openbank.vop.domain.model.VopNoDataReason
import com.openbank.vop.domain.model.VopOutcome
import com.openbank.vop.domain.model.VopVerification
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class VopVerificationServiceTest {

    // A real CZ IBAN — Iban.of validates check digits, so a made-up string would be rejected
    // before any of this logic runs.
    private val domesticIban = "CZ6508000000192000145399"
    private val externalIban = "DE89370400440532013000"

    private val clock: Clock = Clock.fixed(Instant.parse("2026-07-16T10:00:00Z"), ZoneOffset.UTC)
    private val nameLookup = mockk<AccountHolderNameLookupPort>()
    private val schemeRouting = mockk<VopSchemeRoutingPort>()
    private val records = mockk<VopVerificationRecordPort>()

    @BeforeEach
    fun stubRecording() {
        // Must return a real completed Uni, not a relaxed mock: the service chains recording with
        // `.call {}`, so a mock Uni that never emits would hang the whole verification forever.
        every { records.record(any(), any(), any(), any()) } returns Uni.createFrom().voidItem()
    }

    private fun service() = VopVerificationService(
        nameLookup = nameLookup,
        schemeRouting = schemeRouting,
        records = records,
        clock = clock,
        domesticIbanPrefixes = listOf("CZ"),
        maxEditDistance = 1,
    )

    private fun verify(iban: String, name: String): VopVerification =
        service().verify(VerifyPayeeCommand(iban = iban, payeeName = name, requestedBy = "tester"))
            .await().indefinitely()

    @Test
    fun `a domestic IBAN whose holder name matches returns MATCH and discloses nothing`() {
        every { nameLookup.lookupHolderName(domesticIban) } returns Uni.createFrom().item("Jiří Raška")

        val result = verify(domesticIban, "Jiri Raska")

        assertThat(result.outcome).isEqualTo(VopOutcome.MATCH)
        assertThat(result.matchedName).isNull()
        assertThat(result.verifiedAt).isEqualTo(clock.instant())
    }

    @Test
    fun `a near miss returns CLOSE_MATCH and discloses the real name so the payer can correct it`() {
        every { nameLookup.lookupHolderName(domesticIban) } returns Uni.createFrom().item("Jiří Raška")

        val result = verify(domesticIban, "Raška Jiří")

        assertThat(result.outcome).isEqualTo(VopOutcome.CLOSE_MATCH)
        assertThat(result.matchedName).isEqualTo("Jiří Raška")
    }

    @Test
    fun `NO_MATCH never leaks the account-holder name`() {
        every { nameLookup.lookupHolderName(domesticIban) } returns Uni.createFrom().item("Jiří Raška")

        val result = verify(domesticIban, "Petr Novák")

        assertThat(result.outcome).isEqualTo(VopOutcome.NO_MATCH)
        // The whole anti-oracle defence (ADR-0171 §6): an attacker who guesses wrong learns only
        // that they guessed wrong, never the real name.
        assertThat(result.matchedName).isNull()
    }

    @Test
    fun `an unknown IBAN is NO_DATA, not NO_MATCH`() {
        // `null as String?` is required: a bare null resolves to Uni.createFrom().item(Supplier).
        every { nameLookup.lookupHolderName(domesticIban) } returns Uni.createFrom().item(null as String?)

        val result = verify(domesticIban, "Jiří Raška")

        assertThat(result.outcome).isEqualTo(VopOutcome.NO_DATA)
        assertThat(result.noDataReason).isEqualTo(VopNoDataReason.ACCOUNT_NOT_FOUND)
    }

    @Test
    fun `a lookup outage fails OPEN with NO_DATA — never a hold, never a silent MATCH`() {
        // ADR-0171 §3. This is the deliberate inverse of the sanctions gate (ADR-0032), which
        // fails closed. If someone "fixes" VoP to fail closed for consistency with its neighbour,
        // this test is what should stop them.
        every { nameLookup.lookupHolderName(domesticIban) } returns
            Uni.createFrom().failure(NameLookupUnavailableException(RuntimeException("account-service down")))

        val result = verify(domesticIban, "Jiří Raška")

        assertThat(result.outcome).isEqualTo(VopOutcome.NO_DATA)
        assertThat(result.noDataReason).isEqualTo(VopNoDataReason.LOOKUP_UNAVAILABLE)
    }

    @Test
    fun `an external IBAN is routed to the scheme port, not looked up locally`() {
        every { schemeRouting.verifyExternal(externalIban, any()) } returns Uni.createFrom().item(
            VopVerification(
                outcome = VopOutcome.NO_DATA,
                noDataReason = VopNoDataReason.NO_SCHEME_CONNECTIVITY,
                verifiedAt = clock.instant(),
            ),
        )

        val result = verify(externalIban, "Hans Müller")

        assertThat(result.outcome).isEqualTo(VopOutcome.NO_DATA)
        assertThat(result.noDataReason).isEqualTo(VopNoDataReason.NO_SCHEME_CONNECTIVITY)
        verify(exactly = 0) { nameLookup.lookupHolderName(any()) }
    }

    @Test
    fun `every verification is recorded with hashed inputs, never plaintext`() {
        every { nameLookup.lookupHolderName(domesticIban) } returns Uni.createFrom().item("Jiří Raška")
        val ibanHash = slot<String>()
        val nameHash = slot<String>()

        verify(domesticIban, "Jiří Raška")

        verify {
            records.record(
                ibanHash = capture(ibanHash),
                suppliedNameHash = capture(nameHash),
                verification = any(),
                requestedBy = "tester",
            )
        }
        // SHA-256 hex.
        assertThat(ibanHash.captured).hasSize(64).matches("[0-9a-f]{64}")
        assertThat(nameHash.captured).hasSize(64).matches("[0-9a-f]{64}")
        // GDPR Art. 5(1)(c): the evidence row must not carry the inputs in the clear.
        assertThat(ibanHash.captured).doesNotContain(domesticIban)
        assertThat(nameHash.captured).doesNotContain("Raška")
    }
}
