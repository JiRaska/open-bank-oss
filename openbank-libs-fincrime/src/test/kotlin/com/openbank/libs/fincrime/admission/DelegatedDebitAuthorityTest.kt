// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.fincrime.admission

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

class DelegatedDebitAuthorityTest {

    private val account = UUID.randomUUID()
    private val owner = UUID.randomUUID()
    private val delegate = UUID.randomUUID()
    private val now: OffsetDateTime = OffsetDateTime.parse("2026-11-12T12:00:00Z")
    private val capability = "ACCOUNT_INITIATE_PAYMENT"

    private fun grant(
        resourceId: UUID = account,
        grantee: UUID = delegate,
        capabilities: Set<String> = setOf(capability),
        active: Boolean = true,
        validFrom: OffsetDateTime = now.minusDays(1),
        validTo: OffsetDateTime? = now.plusDays(1),
        grantId: UUID = UUID.randomUUID(),
        grantorPartyId: UUID = owner,
    ) = DelegationFact(grantId, grantorPartyId, resourceId, grantee, capabilities, active, validFrom, validTo)

    private fun facts(grants: List<DelegationFact>, actor: UUID = delegate) =
        DebitAuthorityFacts(account, owner, actor, capability, grants, now)

    private fun decided(outcome: StepOutcome) = outcome as StepOutcome.Decided

    @Test
    fun `the owner needs no grant`() {
        val outcome = decided(DelegatedDebitAuthority.evaluate(facts(emptyList(), actor = owner)))

        assertThat(outcome.decision).isEqualTo(AdmissionDecision.ALLOW)
        assertThat(outcome.reason).isEqualTo(DelegatedDebitAuthority.OWNER)
    }

    @Test
    fun `an active grant carrying the capability authorises the delegate`() {
        val outcome = decided(DelegatedDebitAuthority.evaluate(facts(listOf(grant()))))

        assertThat(outcome.decision).isEqualTo(AdmissionDecision.ALLOW)
        assertThat(outcome.reason).isEqualTo(DelegatedDebitAuthority.DELEGATED)
        assertThat(outcome.evidence.attributes).containsEntry("capability", capability)
    }

    @Test
    fun `no grant at all is a definitive refusal, never an outage`() {
        val outcome = decided(DelegatedDebitAuthority.evaluate(facts(emptyList())))

        // REJECT, not Unavailable: "this caller may not" and "I could not find out" are different
        // facts, and the chain maps the second to HOLD. Blurring them is how a fail-closed verdict
        // once hid a plain 401.
        assertThat(outcome.decision).isEqualTo(AdmissionDecision.REJECT)
        assertThat(outcome.reason).isEqualTo(DelegatedDebitAuthority.NO_GRANT)
    }

    @Test
    fun `a grant carrying a DIFFERENT capability does not authorise this action`() {
        val outcome = decided(
            DelegatedDebitAuthority.evaluate(facts(listOf(grant(capabilities = setOf("ACCOUNT_READ_BALANCES"))))),
        )

        // The capability is an argument, never a default. This is the assertion that keeps the
        // ADR-0375 D7 class of defect — a route gated by a neighbouring capability — out of the chain.
        assertThat(outcome.decision).isEqualTo(AdmissionDecision.REJECT)
    }

    @Test
    fun `a grant over a DIFFERENT account does not authorise this one`() {
        val outcome = decided(DelegatedDebitAuthority.evaluate(facts(listOf(grant(resourceId = UUID.randomUUID())))))

        assertThat(outcome.decision).isEqualTo(AdmissionDecision.REJECT)
    }

    @Test
    fun `a grant issued to somebody else does not authorise this caller`() {
        val outcome = decided(DelegatedDebitAuthority.evaluate(facts(listOf(grant(grantee = UUID.randomUUID())))))

        assertThat(outcome.decision).isEqualTo(AdmissionDecision.REJECT)
    }

    @Test
    fun `a suspended or revoked grant does not authorise`() {
        val outcome = decided(DelegatedDebitAuthority.evaluate(facts(listOf(grant(active = false)))))

        assertThat(outcome.decision).isEqualTo(AdmissionDecision.REJECT)
    }

    @Test
    fun `a grant that has not started yet does not authorise`() {
        val outcome = decided(DelegatedDebitAuthority.evaluate(facts(listOf(grant(validFrom = now.plusMinutes(1))))))

        assertThat(outcome.decision).isEqualTo(AdmissionDecision.REJECT)
    }

    @Test
    fun `a grant whose window has closed does not authorise`() {
        val outcome = decided(DelegatedDebitAuthority.evaluate(facts(listOf(grant(validTo = now)))))

        // validTo is exclusive: at the instant it expires the grant is already past.
        assertThat(outcome.decision).isEqualTo(AdmissionDecision.REJECT)
    }

    @Test
    fun `an open-ended grant authorises`() {
        val outcome = decided(DelegatedDebitAuthority.evaluate(facts(listOf(grant(validTo = null)))))

        assertThat(outcome.decision).isEqualTo(AdmissionDecision.ALLOW)
    }

    @Test
    fun `one usable grant among several unusable ones is enough`() {
        val outcome = decided(
            DelegatedDebitAuthority.evaluate(
                facts(listOf(grant(active = false), grant(resourceId = UUID.randomUUID()), grant())),
            ),
        )

        assertThat(outcome.decision).isEqualTo(AdmissionDecision.ALLOW)
    }

    @Test
    fun `an allowed delegated debit names the grant that permitted it`() {
        val grantId = UUID.randomUUID()
        val grantor = UUID.randomUUID()
        val outcome = decided(
            DelegatedDebitAuthority.evaluate(
                facts(listOf(grant(grantId = grantId, grantorPartyId = grantor))),
            ),
        )

        assertThat(outcome.decision).isEqualTo(AdmissionDecision.ALLOW)
        assertThat(outcome.reason).isEqualTo(DelegatedDebitAuthority.DELEGATED)
        assertThat(outcome.evidence.attributes)
            .containsEntry("grant_id", grantId.toString())
            .containsEntry("grantor_party_id", grantor.toString())
            .containsEntry("capability", capability)
    }

    @Test
    fun `the named grant is the one that actually permitted, not the first row`() {
        val wrongCapability = UUID.randomUUID()
        val permitting = UUID.randomUUID()
        val outcome = decided(
            DelegatedDebitAuthority.evaluate(
                facts(
                    listOf(
                        grant(grantId = wrongCapability, capabilities = setOf("ACCOUNT_READ_BALANCES")),
                        grant(grantId = permitting),
                    ),
                ),
            ),
        )

        assertThat(outcome.evidence.attributes).containsEntry("grant_id", permitting.toString())
    }

    @Test
    fun `a refusal names no grant`() {
        val outcome = decided(
            DelegatedDebitAuthority.evaluate(facts(emptyList())),
        )

        assertThat(outcome.decision).isEqualTo(AdmissionDecision.REJECT)
        assertThat(outcome.evidence.attributes).doesNotContainKey("grant_id")
    }
}

/**
 * The pure decision above is not the control; the control is that decision WIRED as the chain's
 * first step. These two assertions are what a rail adopting the chain inherits, and what would
 * break if AUTHORITY were moved later or left unbound.
 */
class DelegatedDebitAuthorityChainTest {

    private val account = UUID.randomUUID()
    private val owner = UUID.randomUUID()
    private val stranger = UUID.randomUUID()
    private val now: OffsetDateTime = OffsetDateTime.parse("2026-11-12T12:00:00Z")

    private fun facts(actor: UUID) = DebitAuthorityFacts(
        accountId = account,
        ownerPartyId = owner,
        actorPartyId = actor,
        requiredCapability = "ACCOUNT_INITIATE_PAYMENT",
        grants = emptyList(),
        now = now,
    )

    private fun chain(calls: MutableList<AdmissionStep>) =
        PaymentAdmissionChain.Builder<DebitAuthorityFacts>(Duration.ofSeconds(1), UnavailablePolicy.HOLD)
            .check(AdmissionStep.AUTHORITY) { f, remaining ->
                calls += AdmissionStep.AUTHORITY
                DelegatedDebitAuthority.check().evaluate(f, remaining)
            }
            .also { builder ->
                AdmissionStep.entries.filter { it != AdmissionStep.AUTHORITY }.forEach { step ->
                    builder.check(step) { _, _ ->
                        calls += step
                        StepOutcome.Decided(AdmissionDecision.ALLOW, AdmissionReasonCode("OK"))
                    }
                }
            }
            .build()

    @Test
    fun `a caller with no authority is rejected before any other control is consulted`(): Unit = runBlocking {
        val calls = mutableListOf<AdmissionStep>()

        val result = chain(calls).admit(facts(stranger))

        assertThat(result.decision).isEqualTo(AdmissionDecision.REJECT)
        // The point of the step being FIRST: a payment nobody is authorised to make costs no
        // sanctions screening, no fraud score and no payee lookup, and tells those controls nothing.
        assertThat(calls).containsExactly(AdmissionStep.AUTHORITY)
        assertThat(result[AdmissionStep.SANCTIONS].outcome).isEqualTo(StepOutcome.NotReached)
    }

    @Test
    fun `the owner passes authority and the rest of the chain still runs`(): Unit = runBlocking {
        val calls = mutableListOf<AdmissionStep>()

        val result = chain(calls).admit(facts(owner))

        assertThat(result.decision).isEqualTo(AdmissionDecision.ALLOW)
        assertThat(calls).isEqualTo(AdmissionStep.entries)
    }
}
