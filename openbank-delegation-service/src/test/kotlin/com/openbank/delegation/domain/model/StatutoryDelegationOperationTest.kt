// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.domain.model

import com.openbank.libs.audit.decision.InputDigest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class StatutoryDelegationOperationTest {
    private val chair = UUID.randomUUID()
    private val member = UUID.randomUUID()
    private val initialChallenge = UUID.randomUUID()
    private val now = Instant.parse("2026-08-01T00:00:00Z")
    private val rule = StatutoryDelegationRule(
        principalPartyId = UUID.randomUUID(),
        caseId = UUID.randomUUID(),
        attestationId = UUID.randomUUID(),
        evidenceDigest = InputDigest.sha256(mapOf("rule" to "synthetic rule")),
        mandatesByRepresentative = linkedMapOf(chair to UUID.randomUUID(), member to UUID.randomUUID()),
        validTo = now.plusSeconds(1_800),
    )
    private val offer = StatutoryDelegationOperation(
        id = UUID.randomUUID(),
        action = StatutoryDelegationAction.ISSUE,
        delegationId = UUID.randomUUID(),
        expectedLifecycleRevision = null,
        initiatorPartyId = chair,
        rule = rule,
        frozenPayload = """{"capabilities":["VIEW_BALANCE"]}""",
        createdAt = now,
        expiresAt = now.plusSeconds(900),
    )

    @Test
    fun `activation requires all current distinct representatives`() {
        val started = offer.sign(chair, initialChallenge, now, rule)
        assertThat(started.sign(chair, initialChallenge, now, rule)).isSameAs(started)
        assertThatIllegalArgumentException().isThrownBy { started.commit(rule, now) }
        assertThatIllegalArgumentException().isThrownBy { started.sign(chair, UUID.randomUUID(), now, rule) }
        assertThatIllegalArgumentException().isThrownBy { started.sign(member, initialChallenge, now, rule) }
        val outsider = UUID.randomUUID()
        assertThatIllegalArgumentException().isThrownBy { started.sign(outsider, UUID.randomUUID(), now, rule) }

        val signed = started.sign(member, UUID.randomUUID(), now, rule)
        assertThat(signed.commit(rule, now).status).isEqualTo(StatutoryDelegationStatus.COMMITTED)
    }

    @Test
    fun `joint quorum also needs distinct representatives for the required offices`() {
        val other = UUID.randomUUID()
        val officeRule = rule.copy(
            mandatesByRepresentative = rule.mandatesByRepresentative + (other to UUID.randomUUID()),
            requiredSignatures = 2,
            requiredOffices = 2,
            officeSlotsByRepresentative = mapOf(chair to setOf(0, 1), member to setOf(0)),
        )
        val started = offer.copy(rule = officeRule).sign(chair, initialChallenge, now, officeRule)
        val wrongOffices = started.sign(other, UUID.randomUUID(), now, officeRule)
        assertThatIllegalArgumentException().isThrownBy { wrongOffices.commit(officeRule, now) }
        val distinctOffices = started.sign(member, UUID.randomUUID(), now, officeRule)
        assertThat(distinctOffices.commit(officeRule, now).status).isEqualTo(StatutoryDelegationStatus.COMMITTED)
    }

    @Test
    fun `changed statutory attestation or mandate prevents signing and committing`() {
        val started = offer.sign(chair, initialChallenge, now, rule)
        val reattested = rule.copy(attestationId = UUID.randomUUID())
        assertThatIllegalArgumentException().isThrownBy { started.sign(member, UUID.randomUUID(), now, reattested) }

        val signed = started.sign(member, UUID.randomUUID(), now, rule)
        val updatedMandates = rule.mandatesByRepresentative + (chair to UUID.randomUUID())
        val replaced = rule.copy(mandatesByRepresentative = updatedMandates)
        assertThatIllegalArgumentException().isThrownBy { signed.commit(replaced, now) }
        assertThatIllegalArgumentException().isThrownBy { signed.commit(rule, offer.expiresAt) }
    }

    @Test
    fun `device link binds the action the grant revision and the frozen terms`() {
        val acceptance = offer.copy(action = StatutoryDelegationAction.ACCEPT, expectedLifecycleRevision = 0)
        assertThat(acceptance.approvalRequestId).isNotEqualTo(offer.approvalRequestId)
        assertThat(acceptance.payloadDigest).isNotEqualTo(offer.payloadDigest)
        assertThat(acceptance.copy(expectedLifecycleRevision = 1).payloadDigest)
            .isNotEqualTo(acceptance.payloadDigest)
        assertThat(offer.copy(frozenPayload = """{"capabilities":["VIEW_TRANSACTIONS"]}""").payloadDigest)
            .isNotEqualTo(offer.payloadDigest)
        val reversedMandates = rule.mandatesByRepresentative.entries.reversed().associate { it.key to it.value }
        val reordered = rule.copy(mandatesByRepresentative = reversedMandates)
        assertThat(offer.copy(rule = reordered).payloadDigest).isEqualTo(offer.payloadDigest)
    }

    @Test
    fun `a verified representative can reject and only the initiator can cancel`() {
        assertThatIllegalArgumentException().isThrownBy { offer.close(member, cancel = true, rule, now) }
        val rejected = offer.close(member, cancel = false, rule, now)
        val cancelled = offer.close(chair, cancel = true, rule, now)

        assertThat(rejected.status).isEqualTo(StatutoryDelegationStatus.REJECTED)
        assertThat(cancelled.status).isEqualTo(StatutoryDelegationStatus.CANCELLED)
        assertThatIllegalArgumentException().isThrownBy { cancelled.sign(member, initialChallenge, now, rule) }
    }
}
