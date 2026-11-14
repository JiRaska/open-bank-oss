// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.fincrime.admission

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AdmissionTypesTest {

    @Test
    fun `the step order is the one ADR-0374 decided, with ADR-0375's authority step first`() {
        // This list is the control, and it is deliberately spelled out rather than derived: the
        // chain executes in declaration order, so reordering these constants is a behaviour change
        // for every rail and must not be possible by accident.
        //
        // AUTHORITY is ADR-0375 D1 amending ADR-0374 D1's six. It goes FIRST by ADR-0374's own
        // ordering rule — cheapest, most local, fully deterministic — and it answers a different
        // question from STANDING: standing asks whether the ACCOUNT may transact, authority asks
        // whether this ACTOR may move it. Added while no rail binds the chain yet, so the blast
        // radius of the order change is zero; after the first rail adopts it, it would not be.
        assertThat(AdmissionStep.entries.map { it.name }).containsExactly(
            "AUTHORITY",
            "STANDING",
            "LIMITS",
            "SANCTIONS",
            "AML_DESTINATION_RISK",
            "FRAUD",
            "PAYEE_VERIFICATION",
        )
    }

    @Test
    fun `severity rises from ALLOW to REJECT`() {
        assertThat(AdmissionDecision.entries.map { it.name }).containsExactly("ALLOW", "CHALLENGE", "HOLD", "REJECT")
        assertThat(AdmissionDecision.ALLOW.combine(AdmissionDecision.HOLD)).isEqualTo(AdmissionDecision.HOLD)
        assertThat(AdmissionDecision.REJECT.combine(AdmissionDecision.CHALLENGE)).isEqualTo(AdmissionDecision.REJECT)
    }

    @Test
    fun `an unavailable control can only hold or reject`() {
        assertThat(UnavailablePolicy.entries.map { it.decision })
            .containsExactly(AdmissionDecision.HOLD, AdmissionDecision.REJECT)
    }

    @Test
    fun `a reason code is upper snake case and bounded`() {
        assertThat(AdmissionReasonCode("ACCOUNT_FROZEN").toString()).isEqualTo("ACCOUNT_FROZEN")
        listOf("", "A", "account_frozen", "1ABC", "HAS SPACE", "A".repeat(65)).forEach { bad ->
            assertThatThrownBy { AdmissionReasonCode(bad) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `evidence is ordered by key and compares by content`() {
        val evidence = AdmissionEvidence.of("score" to "0.7", "list" to "EU")
        assertThat(evidence.attributes.keys).containsExactly("list", "score")
        assertThat(evidence).isEqualTo(AdmissionEvidence.of(mapOf("list" to "EU", "score" to "0.7")))
        assertThat(evidence.hashCode()).isEqualTo(AdmissionEvidence.of("list" to "EU", "score" to "0.7").hashCode())
        assertThat(evidence).isNotEqualTo(AdmissionEvidence.NONE)
        assertThat(evidence.toString()).contains("list=EU")
    }

    @Test
    fun `evidence refuses what would not fit a case or an outbox row`() {
        val tooMany = (0..AdmissionEvidence.MAX_ATTRIBUTES).associate { "k$it" to "v" }
        assertThatThrownBy { AdmissionEvidence.of(tooMany) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AdmissionEvidence.of("Bad-Key" to "v") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AdmissionEvidence.of("k" to "v".repeat(AdmissionEvidence.MAX_VALUE_LENGTH + 1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
