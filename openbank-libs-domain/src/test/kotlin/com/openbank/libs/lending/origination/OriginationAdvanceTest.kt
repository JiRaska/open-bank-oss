// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.lending.origination

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Forward drive along the ADR-0211 origination path. The whole point is that the compliance pack
 * decides which optional states are visited: a bug that always skipped (or always visited) them
 * would drop a statutory reflection wait or a document step without any transition being illegal.
 */
class OriginationAdvanceTest {

    private val none = emptySet<OriginationState>()

    @Test
    fun `with no mandatory optional steps the optional states are skipped entirely`() {
        assertThat(OriginationAdvance.nextState(OriginationState.KYC_PENDING, none))
            .isEqualTo(OriginationState.ASSESSMENT)
        assertThat(OriginationAdvance.nextState(OriginationState.SIGNED, none))
            .isEqualTo(OriginationState.READY_TO_DISBURSE)
    }

    @Test
    fun `a mandatory optional step is visited instead of skipped`() {
        assertThat(OriginationAdvance.nextState(OriginationState.KYC_PENDING, setOf(OriginationState.DOCS_REQUIRED)))
            .isEqualTo(OriginationState.DOCS_REQUIRED)
        assertThat(
            OriginationAdvance.nextState(OriginationState.SIGNED, setOf(OriginationState.REFLECTION_PERIOD)),
        ).isEqualTo(OriginationState.REFLECTION_PERIOD)
    }

    @Test
    fun `marking one optional state mandatory does not pull the other one in`() {
        assertThat(OriginationAdvance.nextState(OriginationState.KYC_PENDING, setOf(OriginationState.REFLECTION_PERIOD)))
            .isEqualTo(OriginationState.ASSESSMENT)
    }

    @Test
    fun `advancing out of an optional state continues down the path`() {
        assertThat(OriginationAdvance.nextState(OriginationState.DOCS_REQUIRED, none))
            .isEqualTo(OriginationState.ASSESSMENT)
        assertThat(OriginationAdvance.nextState(OriginationState.REFLECTION_PERIOD, none))
            .isEqualTo(OriginationState.READY_TO_DISBURSE)
    }

    @Test
    fun `advancement stops at READY_TO_DISBURSE - disbursement is never an advance`() {
        assertThat(OriginationAdvance.nextState(OriginationState.READY_TO_DISBURSE, none)).isNull()
        assertThat(
            OriginationAdvance.nextState(OriginationState.READY_TO_DISBURSE, OriginationState.entries.toSet()),
        ).isNull()
    }

    @Test
    fun `terminal and off-path states have no forward drive`() {
        OriginationState.TERMINAL.forEach { s ->
            assertThat(OriginationAdvance.nextState(s, none)).describedAs("next after %s", s).isNull()
        }
    }

    @Test
    fun `driving from DRAFT with nothing mandatory reaches READY_TO_DISBURSE and skips both optionals`() {
        val path = generateSequence(OriginationState.DRAFT) { OriginationAdvance.nextState(it, none) }.toList()
        assertThat(path).endsWith(OriginationState.READY_TO_DISBURSE)
        assertThat(path).doesNotContain(OriginationState.DOCS_REQUIRED, OriginationState.REFLECTION_PERIOD)
        assertThat(path).startsWith(OriginationState.DRAFT, OriginationState.SUBMITTED, OriginationState.KYC_PENDING)
    }

    @Test
    fun `driving from DRAFT with both optionals mandatory visits every state on the forward path`() {
        val mandatory = setOf(OriginationState.DOCS_REQUIRED, OriginationState.REFLECTION_PERIOD)
        val path = generateSequence(OriginationState.DRAFT) { OriginationAdvance.nextState(it, mandatory) }.toList()
        assertThat(path).containsSequence(
            OriginationState.KYC_PENDING,
            OriginationState.DOCS_REQUIRED,
            OriginationState.ASSESSMENT,
        )
        assertThat(path).containsSequence(
            OriginationState.SIGNED,
            OriginationState.REFLECTION_PERIOD,
            OriginationState.READY_TO_DISBURSE,
        )
        assertThat(path).hasSize(12)
    }

    @Test
    fun `every consecutive pair the advancer produces is a legal transition in the standard policy`() {
        val policy = OriginationTransitionPolicy.standard()
        listOf(none, setOf(OriginationState.DOCS_REQUIRED, OriginationState.REFLECTION_PERIOD)).forEach { mandatory ->
            val path = generateSequence(OriginationState.DRAFT) { OriginationAdvance.nextState(it, mandatory) }.toList()
            path.zipWithNext().forEach { (from, to) ->
                assertThat(policy.isAllowed(from, to)).describedAs("%s -> %s", from, to).isTrue()
            }
        }
    }
}
