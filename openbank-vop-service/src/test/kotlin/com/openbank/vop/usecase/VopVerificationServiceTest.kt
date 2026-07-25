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
import com.openbank.vop.infrastructure.observability.VopMetricsAdapter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
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

    // The REAL metrics adapter over a SimpleMeterRegistry rather than a mock port, so the
    // instrumentation assertions below fail if the service stops emitting.
    private val registry = SimpleMeterRegistry()

    private fun service() = VopVerificationService(
        nameLookup = nameLookup,
        schemeRouting = schemeRouting,
        records = records,
        metrics = VopMetricsAdapter(registry),
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

    @Test
    fun `a domestic verification is counted by route and outcome, and timed`() {
        every { nameLookup.lookupHolderName(domesticIban) } returns Uni.createFrom().item("Jiří Raška")

        verify(domesticIban, "Petr Novák")

        assertThat(verifications(route = "domestic", outcome = "NO_MATCH")).isEqualTo(1.0)
        assertThat(
            registry.get("openbank.vop.verification.duration")
                .tag("service", "vop").tag("route", "domestic").timer().count(),
        ).isEqualTo(1L)
    }

    @Test
    fun `a lookup outage is counted as no_data with reason lookup_unavailable, not as a match`() {
        // The whole point of the meter: failing open (ADR-0171 §3) means a total account-service
        // outage looks exactly like normal traffic from every angle except this series.
        every { nameLookup.lookupHolderName(domesticIban) } returns
            Uni.createFrom().failure(NameLookupUnavailableException(RuntimeException("account-service down")))

        verify(domesticIban, "Jiří Raška")

        assertThat(verifications(route = "domestic", outcome = "NO_DATA")).isEqualTo(1.0)
        assertThat(
            registry.get("openbank.vop.no_data")
                .tag("service", "vop").tag("route", "domestic").tag("reason", "lookup_unavailable")
                .counter().count(),
        ).isEqualTo(1.0)
    }

    @Test
    fun `an external verification is tagged route=external`() {
        every { schemeRouting.verifyExternal(externalIban, any()) } returns Uni.createFrom().item(
            VopVerification(
                outcome = VopOutcome.NO_DATA,
                noDataReason = VopNoDataReason.NO_SCHEME_CONNECTIVITY,
                verifiedAt = clock.instant(),
            ),
        )

        verify(externalIban, "Hans Müller")

        assertThat(verifications(route = "external", outcome = "NO_DATA")).isEqualTo(1.0)
        assertThat(
            registry.get("openbank.vop.no_data")
                .tag("route", "external").tag("reason", "no_scheme_connectivity").counter().count(),
        ).isEqualTo(1.0)
    }

    @Test
    fun `a MATCH publishes no no_data series at all`() {
        // no_data is a second series rather than a tag precisely so a MATCH does not carry a
        // reason label it never varies over.
        every { nameLookup.lookupHolderName(domesticIban) } returns Uni.createFrom().item("Jiří Raška")

        verify(domesticIban, "Jiri Raska")

        assertThat(verifications(route = "domestic", outcome = "MATCH")).isEqualTo(1.0)
        assertThat(registry.find("openbank.vop.no_data").counters()).isEmpty()
    }

    private fun verifications(route: String, outcome: String): Double = registry.get("openbank.vop.verifications")
        .tag("service", "vop")
        .tag("route", route)
        .tag("outcome", outcome)
        .counter().count()
}
