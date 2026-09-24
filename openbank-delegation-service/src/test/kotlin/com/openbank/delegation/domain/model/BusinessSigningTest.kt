// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class BusinessSigningTest {
    private val entity = UUID.randomUUID()
    private val a = UUID.randomUUID()
    private val b = UUID.randomUUID()
    private val c = UUID.randomUUID()
    private val now = Instant.parse("2026-09-19T10:00:00Z")

    private fun czk(v: String) = SigningAmount(BigDecimal(v), "CZK")

    private fun payment(v: String, iban: String = "CZ6508000000192000145399") = PaymentFacts(czk(v), iban, "DOMESTIC")

    private val bands = SigningPolicy(
        entityPartyId = entity,
        version = 3,
        rules = listOf(
            SigningPolicyRule(maxAmount = czk("50000"), currency = "CZK", requiredSignatures = 1),
            SigningPolicyRule(requiredSignatures = 2, mustIncludeGroupId = "jednatele"),
        ),
    )
    private val groups = mapOf("jednatele" to SignerGroup("jednatele", entity, "Jednatelé", setOf(a)))

    @Test
    fun `a SOLE register derives one signature and a JOINT register its requiredSignatures`() {
        val sole = SigningPolicy.derived(entity, listOf(RepresentationMandate(a, MandateAuthority.SOLE, 1)))
        val joint = SigningPolicy.derived(
            entity,
            listOf(
                RepresentationMandate(a, MandateAuthority.SOLE, 1),
                RepresentationMandate(b, MandateAuthority.JOINT, 3),
            ),
        )
        assertThat(sole.rules.single().requiredSignatures).isEqualTo(1)
        assertThat(
            joint.rules.single().requiredSignatures,
        ).describedAs("a mixed register never yields fewer than its JOINT clause").isEqualTo(3)
        assertThat(joint.derivedFromRegister).isTrue()
        assertThat(joint.version).isZero()
    }

    @Test
    fun `bands match first-first with inclusive bounds and fall back to the strictest rule`() {
        val atLimit = SigningPolicyEvaluator.evaluatePayment(bands, setOf(a, b), groups, emptySet(), payment("50000"))
        val above = SigningPolicyEvaluator.evaluatePayment(bands, setOf(a, b), groups, emptySet(), payment("50000.01"))
        val eur = SigningPolicyEvaluator.evaluatePayment(
            bands.copy(
                rules = listOf(
                    SigningPolicyRule(maxAmount = czk("50000"), requiredSignatures = 1),
                    SigningPolicyRule(currency = "CZK", requiredSignatures = 2),
                ),
            ),
            setOf(a, b),
            emptyMap(),
            emptySet(),
            PaymentFacts(SigningAmount(BigDecimal.ONE, "EUR"), "CZ6508000000192000145399", "SEPA"),
        )
        assertThat(atLimit.required).isEqualTo(1)
        assertThat(above.required).isEqualTo(2)
        assertThat(above.mustIncludeSignerIds).containsExactly(a)
        assertThat(eur.required).describedAs("no band matches EUR ⇒ strictest").isEqualTo(2)
    }

    @Test
    fun `a trusted payee needs one signature until the cap is exceeded`() {
        val capped = bands.copy(trustedPayeeCap = czk("200000"))
        val trusted = setOf("CZ6508000000192000145399")
        assertThat(
            SigningPolicyEvaluator.evaluatePayment(
                capped,
                setOf(a, b),
                groups,
                trusted,
                payment("150000", "cz65 0800 0000 1920 0014 5399"),
            ).required,
        )
            .isEqualTo(1)
        assertThat(
            SigningPolicyEvaluator.evaluatePayment(capped, setOf(a, b), groups, trusted, payment("200000.01")).trusted,
        ).isFalse()
    }

    @Test
    fun `administrative changes always take the strictest rule`() {
        val evaluation = SigningPolicyEvaluator.evaluateAdministrative(bands, setOf(a, b), groups)
        assertThat(evaluation.required).isEqualTo(2)
        assertThat(evaluation.trusted).isFalse()
    }

    @Test
    fun `a group member without a live mandate is not eligible`() {
        val policy = bands.copy(rules = listOf(SigningPolicyRule(requiredSignatures = 2, groupId = "finance")))
        val finance = mapOf("finance" to SignerGroup("finance", entity, "Finance", setOf(a, c)))
        val evaluation = SigningPolicyEvaluator.evaluatePayment(policy, setOf(a, b), finance, emptySet(), payment("1"))
        assertThat(evaluation.eligibleSignerIds).containsExactly(a)
        assertThat(evaluation.satisfiable).isFalse()
    }

    private fun request(required: Int = 2, mustInclude: Set<UUID> = emptySet()) = ApprovalRequest(
        id = UUID.randomUUID(),
        entityPartyId = entity,
        kind = ApprovalKind.PAYMENT,
        payload = "{}",
        payloadSha256 = "0".repeat(64),
        summary = null,
        policyVersion = 0,
        required = required,
        eligibleSignerIds = setOf(a, b, c),
        mustIncludeGroupId = if (mustInclude.isEmpty()) null else "jednatele",
        mustIncludeSignerIds = mustInclude,
        initiatorPartyId = a,
        signatures = emptyList(),
        status = ApprovalStatus.PENDING,
        rejection = null,
        expiresAt = now.plusSeconds(3600),
        createdAt = now,
    )

    @Test
    fun `N distinct signatures approve, and the same person never counts twice`() {
        val first = request().sign(a, UUID.randomUUID(), now)
        assertThat(first.status).isEqualTo(ApprovalStatus.PENDING)
        assertThatThrownBy { first.sign(a, UUID.randomUUID(), now) }
            .isInstanceOfSatisfying(SignatureRefusedException::class.java) {
                assertThat(it.refusal).isEqualTo(SignatureRefusal.ALREADY_SIGNED)
            }
        assertThat(first.sign(b, UUID.randomUUID(), now).status).isEqualTo(ApprovalStatus.APPROVED)
    }

    @Test
    fun `must-include is required on top of the count`() {
        val signed = request(mustInclude = setOf(c)).sign(a, UUID.randomUUID(), now).sign(b, UUID.randomUUID(), now)
        assertThat(signed.status).isEqualTo(ApprovalStatus.PENDING)
        assertThat(signed.sign(c, UUID.randomUUID(), now).status).isEqualTo(ApprovalStatus.APPROVED)
    }

    @Test
    fun `an outsider, an expired request and a closed request refuse signatures`() {
        val outsider = UUID.randomUUID()
        assertThatThrownBy { request().sign(outsider, UUID.randomUUID(), now) }
            .isInstanceOfSatisfying(SignatureRefusedException::class.java) {
                assertThat(it.refusal).isEqualTo(SignatureRefusal.NOT_ELIGIBLE)
            }
        assertThatThrownBy { request().sign(b, UUID.randomUUID(), now.plusSeconds(3600)) }
            .isInstanceOfSatisfying(SignatureRefusedException::class.java) {
                assertThat(it.refusal).isEqualTo(SignatureRefusal.EXPIRED)
            }
        val rejected = request().reject(b, "no", now)
        assertThatThrownBy { rejected.sign(c, UUID.randomUUID(), now) }
            .isInstanceOfSatisfying(SignatureRefusedException::class.java) {
                assertThat(it.refusal).isEqualTo(SignatureRefusal.NOT_PENDING)
            }
    }

    @Test
    fun `IBANs compare in one normal form`() {
        assertThat(Iban.normalize(" cz65 0800 0000 1920 0014 5399 ")).isEqualTo("CZ6508000000192000145399")
        assertThatThrownBy { Iban.normalize("not-an-iban") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
