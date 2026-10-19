// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.reference

import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorNotPermittedException
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.domain.model.FourEyesViolationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class ReferenceRecordTest {
    private val now = Instant.parse("2026-10-19T08:00:00Z")
    private val editor = Actor("ref-editor-1", ActorType.HUMAN)
    private val approver = Actor("approver-1", ActorType.HUMAN)
    private val agent = Actor("agent:treasury-drafter", ActorType.AI_AGENT)
    private val service = Actor("service-account-x", ActorType.SERVICE)

    private fun calendar(code: String = "CERTIS", extra: List<LocalDate> = emptyList()) =
        CalendarPayload(code, BaseCalendar.CERTIS, extra)

    private fun proposed(by: Actor = editor, version: Int = 1, from: String = "2026-01-01", to: String? = null) =
        ReferenceRecord.propose(
            ReferenceKind.CALENDAR,
            "CERTIS",
            version,
            LocalDate.parse(from),
            to?.let(LocalDate::parse),
            calendar(),
            "bank closure added",
            by,
            now,
        )

    @Nested
    inner class FourEyes {
        @Test
        fun `a different human approves and the record becomes ACTIVE`() {
            val active = proposed().approve(approver, now)
            assertThat(active.status).isEqualTo(ReferenceStatus.ACTIVE)
            assertThat(active.approvedBy).isEqualTo(approver)
            assertThat(active.isEffectiveOn(LocalDate.parse("2026-06-01"))).isTrue()
        }

        @Test
        fun `the proposer cannot approve their own proposal`() {
            assertThatThrownBy { proposed().approve(editor, now) }.isInstanceOf(FourEyesViolationException::class.java)
        }

        @Test
        fun `the proposer cannot reject or verify their own proposal`() {
            assertThatThrownBy {
                proposed().reject(editor, "no", now)
            }.isInstanceOf(FourEyesViolationException::class.java)
            assertThatThrownBy { proposed().verify(editor) }.isInstanceOf(FourEyesViolationException::class.java)
        }

        @Test
        fun `an AI agent may propose but never approve, reject or verify`() {
            val byAgent = proposed(by = agent)
            assertThat(byAgent.status).isEqualTo(ReferenceStatus.PENDING)
            assertThatThrownBy { proposed().approve(agent, now) }.isInstanceOf(ActorNotPermittedException::class.java)
            assertThatThrownBy {
                proposed().reject(agent, "x", now)
            }.isInstanceOf(ActorNotPermittedException::class.java)
            assertThatThrownBy { proposed().verify(agent) }.isInstanceOf(ActorNotPermittedException::class.java)
        }

        @Test
        fun `the agent is refused as an agent even on its own proposal`() {
            assertThatThrownBy { proposed(by = agent).approve(agent, now) }
                .isInstanceOf(ActorNotPermittedException::class.java)
        }

        @Test
        fun `a service account may not even propose`() {
            assertThatThrownBy { proposed(by = service) }.isInstanceOf(ActorNotPermittedException::class.java)
        }

        @Test
        fun `only a PENDING version can be approved or rejected`() {
            val active = proposed().approve(approver, now)
            assertThatThrownBy { active.approve(Actor("approver-2", ActorType.HUMAN), now) }
                .isInstanceOf(IllegalStateException::class.java)
            assertThatThrownBy { active.reject(Actor("approver-2", ActorType.HUMAN), "late", now) }
                .isInstanceOf(IllegalStateException::class.java)
        }

        @Test
        fun `a rejection needs a reason and records it`() {
            assertThatThrownBy {
                proposed().reject(approver, " ", now)
            }.isInstanceOf(IllegalArgumentException::class.java)
            val rejected = proposed().reject(approver, "wrong date", now)
            assertThat(rejected.status).isEqualTo(ReferenceStatus.REJECTED)
            assertThat(rejected.decisionReason).isEqualTo("wrong date")
            assertThat(rejected.isEffectiveOn(LocalDate.parse("2026-06-01"))).isFalse()
        }

        @Test
        fun `verify flags provenance once, by another human`() {
            val verified = proposed().approve(approver, now).verify(approver)
            assertThat(verified.verified).isTrue()
            assertThat(verified.verifiedBy).isEqualTo(approver)
            assertThatThrownBy { verified.verify(Actor("approver-2", ActorType.HUMAN)) }
                .isInstanceOf(IllegalStateException::class.java)
            assertThatThrownBy { proposed().reject(approver, "x", now).verify(approver) }
                .isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Nested
    inner class Shape {
        @Test
        fun `a proposal needs a reason`() {
            assertThatThrownBy {
                ReferenceRecord.propose(
                    ReferenceKind.CALENDAR, "CERTIS", 1,
                    LocalDate.parse(
                        "2026-01-01",
                    ),
                    null, calendar(), "", editor, now,
                )
            }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `the path key must match the payload's key`() {
            assertThatThrownBy {
                ReferenceRecord.propose(
                    ReferenceKind.CALENDAR, "TARGET2", 1,
                    LocalDate.parse(
                        "2026-01-01",
                    ),
                    null, calendar(), "r", editor, now,
                )
            }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("does not match")
        }

        @Test
        fun `a payload of another kind is refused`() {
            assertThatThrownBy {
                ReferenceRecord.propose(
                    ReferenceKind.INDEX, "CERTIS", 1, LocalDate.parse("2026-01-01"), null, calendar(), "r", editor, now,
                )
            }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `validTo must follow validFrom`() {
            assertThatThrownBy { proposed(from = "2026-02-01", to = "2026-02-01") }
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `effective dating is half-open`() {
            val r = proposed(from = "2026-01-01", to = "2026-07-01").approve(approver, now)
            assertThat(r.isEffectiveOn(LocalDate.parse("2025-12-31"))).isFalse()
            assertThat(r.isEffectiveOn(LocalDate.parse("2026-01-01"))).isTrue()
            assertThat(r.isEffectiveOn(LocalDate.parse("2026-06-30"))).isTrue()
            assertThat(r.isEffectiveOn(LocalDate.parse("2026-07-01"))).isFalse()
        }

        @Test
        fun `a PENDING version is never effective`() {
            assertThat(proposed().isEffectiveOn(LocalDate.parse("2026-06-01"))).isFalse()
        }
    }

    @Nested
    inner class Supersession {
        private val v1 = proposed(version = 1, from = "2026-01-01").approve(approver, now)

        @Test
        fun `a later open-ended version closes the earlier one at its start`() {
            val v2 = proposed(version = 2, from = "2026-07-01").approve(approver, now)
            val changed = ReferenceSupersession.resolve(v2, listOf(v1))
            assertThat(changed).hasSize(1)
            assertThat(changed.single().validTo).isEqualTo(LocalDate.parse("2026-07-01"))
            assertThat(changed.single().status).isEqualTo(ReferenceStatus.ACTIVE)
            assertThat(changed.single().isEffectiveOn(LocalDate.parse("2026-06-30"))).isTrue()
            assertThat(v2.isEffectiveOn(LocalDate.parse("2026-07-01"))).isTrue()
        }

        @Test
        fun `a version starting no later replaces the earlier one entirely`() {
            val v2 = proposed(version = 2, from = "2026-01-01").approve(approver, now)
            val changed = ReferenceSupersession.resolve(v2, listOf(v1))
            assertThat(changed.single().status).isEqualTo(ReferenceStatus.SUPERSEDED)
        }

        @Test
        fun `a bounded version inside an open one would split it and is refused`() {
            val v2 = proposed(version = 2, from = "2026-03-01", to = "2026-04-01").approve(approver, now)
            assertThatThrownBy { ReferenceSupersession.resolve(v2, listOf(v1)) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("split")
        }

        @Test
        fun `a non-overlapping version leaves the others alone`() {
            val bounded = proposed(version = 1, from = "2026-01-01", to = "2026-03-01").approve(approver, now)
            val v2 = proposed(version = 2, from = "2026-03-01").approve(approver, now)
            assertThat(ReferenceSupersession.resolve(v2, listOf(bounded))).isEmpty()
        }

        @Test
        fun `another key is never touched`() {
            val target = ReferenceRecord.propose(
                ReferenceKind.CALENDAR,
                "TARGET2",
                1,
                LocalDate.parse("2026-01-01"),
                null,
                calendar("TARGET2"),
                "r",
                editor,
                now,
            ).approve(approver, now)
            val v2 = proposed(version = 2, from = "2026-01-01").approve(approver, now)
            assertThat(ReferenceSupersession.resolve(v2, listOf(target))).isEmpty()
        }
    }
}
