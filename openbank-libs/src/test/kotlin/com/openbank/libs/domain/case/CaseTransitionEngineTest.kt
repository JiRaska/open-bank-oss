// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.case

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class CaseTransitionEngineTest {

    private val occurredAt: Instant = Instant.parse("2026-05-28T10:15:30Z")
    private val caseId = CaseId(UUID.fromString("11111111-1111-1111-1111-111111111111"))

    @Nested
    inner class Guarding {

        private val engine = CaseTransitionEngine()

        @Test
        fun `allows configured transition`() {
            val transition = transition(from = CaseStatus.DRAFT, to = CaseStatus.OPEN)

            val result = engine.guard(transition)

            assertThat(result).isEqualTo(CaseTransitionGuardResult.Allowed)
        }

        @Test
        fun `rejects transition outside policy`() {
            val transition = transition(from = CaseStatus.DRAFT, to = CaseStatus.CLOSED)

            val result = engine.guard(transition)

            assertThat(result)
                .isEqualTo(CaseTransitionGuardResult.Rejected("Transition from DRAFT to CLOSED is not allowed"))
        }

        @Test
        fun `rejects allowed transition when actor is blank`() {
            val transition = transition(
                from = CaseStatus.OPEN,
                to = CaseStatus.IN_REVIEW,
                actor = "   ",
            )

            val result = engine.guard(transition)

            assertThat(result).isEqualTo(CaseTransitionGuardResult.Rejected("Actor must not be blank"))
        }

        @Test
        fun `rejects transition when custom guard fails`() {
            val policy = CaseTransitionPolicy.standard().copy(
                guards = mapOf(
                    CaseTransitionKey(CaseStatus.OPEN, CaseStatus.IN_REVIEW) to listOf(
                        CaseTransitionGuard { candidate ->
                            if (candidate.metadata.containsKey("reviewerId")) {
                                null
                            } else {
                                CaseGuardFailure("reviewerId metadata is required")
                            }
                        },
                    ),
                ),
            )
            val engine = CaseTransitionEngine(policy)

            val result = engine.guard(
                transition(from = CaseStatus.OPEN, to = CaseStatus.IN_REVIEW, metadata = mapOf("channel" to "api")),
            )

            assertThat(result).isEqualTo(CaseTransitionGuardResult.Rejected("reviewerId metadata is required"))
        }
    }

    @Nested
    inner class Applying {

        private val engine = CaseTransitionEngine()

        @Test
        fun `applies valid transition and emits timeline event metadata`() {
            val transition = transition(
                from = CaseStatus.DRAFT,
                to = CaseStatus.OPEN,
                metadata = mapOf("source" to "api", "priority" to CasePriority.HIGH.name),
            )

            val result = engine.apply(transition)

            assertThat(result).isInstanceOf(CaseTransitionResult.Applied::class.java)
            val applied = result as CaseTransitionResult.Applied
            assertThat(applied.newStatus).isEqualTo(CaseStatus.OPEN)
            assertThat(applied.timelineEvent).isEqualTo(
                CaseTimelineEvent(
                    caseId = caseId,
                    caseType = CaseType.PID_VERIFICATION,
                    fromStatus = CaseStatus.DRAFT,
                    toStatus = CaseStatus.OPEN,
                    reasonCode = CaseReasonCode.CREATED,
                    actor = "case-engine-test",
                    occurredAt = occurredAt,
                    metadata = linkedMapOf(
                        "caseType" to CaseType.PID_VERIFICATION.name,
                        "fromStatus" to CaseStatus.DRAFT.name,
                        "toStatus" to CaseStatus.OPEN.name,
                        "reasonCode" to CaseReasonCode.CREATED.name,
                        "actor" to "case-engine-test",
                        "priority" to CasePriority.HIGH.name,
                        "source" to "api",
                    ),
                ),
            )
        }

        @Test
        fun `returns rejected result for invalid transition`() {
            val result = engine.apply(transition(from = CaseStatus.CANCELLED, to = CaseStatus.APPROVED))

            assertThat(result).isEqualTo(
                CaseTransitionResult.Rejected("Transition from CANCELLED to APPROVED is not allowed"),
            )
        }
    }

    @Test
    fun `creates case id from string`() {
        val value = "22222222-2222-2222-2222-222222222222"

        assertThat(CaseId.from(value).value).isEqualTo(UUID.fromString(value))
    }

    private fun transition(
        from: CaseStatus,
        to: CaseStatus,
        actor: String = "case-engine-test",
        metadata: Map<String, String> = emptyMap(),
    ): CaseTransition = CaseTransition(
        caseId = caseId,
        caseType = CaseType.PID_VERIFICATION,
        fromStatus = from,
        toStatus = to,
        reasonCode = when (to) {
            CaseStatus.OPEN -> if (from == CaseStatus.DRAFT) CaseReasonCode.CREATED else CaseReasonCode.REOPENED
            CaseStatus.IN_REVIEW -> CaseReasonCode.REVIEW_STARTED
            CaseStatus.CLOSED -> CaseReasonCode.CLOSED
            CaseStatus.CANCELLED -> CaseReasonCode.CANCELLED
            CaseStatus.REJECTED -> CaseReasonCode.REJECTED
            CaseStatus.APPROVED -> CaseReasonCode.APPROVED
            CaseStatus.WAITING_FOR_CUSTOMER -> CaseReasonCode.INFORMATION_REQUESTED
            CaseStatus.WAITING_FOR_EXTERNAL_PARTY -> CaseReasonCode.EXTERNAL_DEPENDENCY
            CaseStatus.DRAFT -> CaseReasonCode.MANUAL_UPDATE
        },
        actor = actor,
        occurredAt = occurredAt,
        metadata = metadata,
    )
}
