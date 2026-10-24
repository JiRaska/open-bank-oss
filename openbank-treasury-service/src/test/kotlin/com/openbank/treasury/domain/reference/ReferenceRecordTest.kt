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
    private val today = LocalDate.parse("2026-10-19")
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
            today,
        )

    @Nested
    inner class FourEyes {
        @Test
        fun `a different human approves and the record becomes ACTIVE`() {
            val active = proposed().approve(approver, now, today)
            assertThat(active.status).isEqualTo(ReferenceStatus.ACTIVE)
            assertThat(active.approvedBy).isEqualTo(approver)
            assertThat(active.isEffectiveOn(LocalDate.parse("2026-06-01"))).isTrue()
        }

        @Test
        fun `the proposer cannot approve their own proposal`() {
            assertThatThrownBy {
                proposed().approve(editor, now, today)
            }.isInstanceOf(FourEyesViolationException::class.java)
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
            assertThatThrownBy {
                proposed().approve(agent, now, today)
            }.isInstanceOf(ActorNotPermittedException::class.java)
            assertThatThrownBy {
                proposed().reject(agent, "x", now)
            }.isInstanceOf(ActorNotPermittedException::class.java)
            assertThatThrownBy { proposed().verify(agent) }.isInstanceOf(ActorNotPermittedException::class.java)
        }

        @Test
        fun `the agent is refused as an agent even on its own proposal`() {
            assertThatThrownBy { proposed(by = agent).approve(agent, now, today) }
                .isInstanceOf(ActorNotPermittedException::class.java)
        }

        @Test
        fun `a service account may not even propose`() {
            assertThatThrownBy { proposed(by = service) }.isInstanceOf(ActorNotPermittedException::class.java)
        }

        @Test
        fun `only a PENDING version can be approved or rejected`() {
            val active = proposed().approve(approver, now, today)
            assertThatThrownBy { active.approve(Actor("approver-2", ActorType.HUMAN), now, today) }
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
            val verified = proposed().approve(approver, now, today).verify(approver)
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
                    today,
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
                    today,
                )
            }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("does not match")
        }

        @Test
        fun `a payload of another kind is refused`() {
            assertThatThrownBy {
                ReferenceRecord.propose(
                    ReferenceKind.INDEX, "CERTIS", 1, LocalDate.parse("2026-01-01"), null, calendar(), "r", editor, now,
                    today,
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
            val r = proposed(from = "2026-01-01", to = "2026-07-01").approve(approver, now, today)
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
        private val v1 = proposed(version = 1, from = "2026-01-01").approve(approver, now, today)

        @Test
        fun `a later open-ended version closes the earlier one at its start`() {
            val v2 = proposed(version = 2, from = "2026-07-01").approve(approver, now, today)
            val changed = ReferenceSupersession.resolve(v2, listOf(v1))
            assertThat(changed).hasSize(1)
            assertThat(changed.single().validTo).isEqualTo(LocalDate.parse("2026-07-01"))
            assertThat(changed.single().status).isEqualTo(ReferenceStatus.ACTIVE)
            assertThat(changed.single().isEffectiveOn(LocalDate.parse("2026-06-30"))).isTrue()
            assertThat(v2.isEffectiveOn(LocalDate.parse("2026-07-01"))).isTrue()
        }

        @Test
        fun `a version starting no later replaces the earlier one entirely`() {
            val v2 = proposed(version = 2, from = "2026-01-01").approve(approver, now, today)
            val changed = ReferenceSupersession.resolve(v2, listOf(v1))
            assertThat(changed.single().status).isEqualTo(ReferenceStatus.SUPERSEDED)
        }

        @Test
        fun `a bounded version inside an open one would split it and is refused`() {
            val v2 = proposed(version = 2, from = "2026-03-01", to = "2026-04-01").approve(approver, now, today)
            assertThatThrownBy { ReferenceSupersession.resolve(v2, listOf(v1)) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("split")
        }

        @Test
        fun `a non-overlapping version leaves the others alone`() {
            val bounded = proposed(version = 1, from = "2026-01-01", to = "2026-03-01").approve(approver, now, today)
            val v2 = proposed(version = 2, from = "2026-03-01").approve(approver, now, today)
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
                today,
            ).approve(approver, now, today)
            val v2 = proposed(version = 2, from = "2026-01-01").approve(approver, now, today)
            assertThat(ReferenceSupersession.resolve(v2, listOf(target))).isEmpty()
        }
    }

    @Nested
    inner class CounterpartyLimits {
        private val senior = Actor("senior-1", ActorType.HUMAN)

        private fun cp(czk: String, eur: String? = null) = CounterpartyPayload(
            "SIMBK-A",
            "Bank A",
            ReferenceCounterpartyKind.BANK,
            null,
            "CZ",
            buildMap {
                put("CZK", java.math.BigDecimal(czk))
                eur?.let { put("EUR", java.math.BigDecimal(it)) }
            },
        )

        private fun version(v: Int, payload: CounterpartyPayload, from: LocalDate = today) = ReferenceRecord.propose(
            ReferenceKind.COUNTERPARTY,
            "SIMBK-A",
            v,
            from,
            null,
            payload,
            "limit review",
            editor,
            now,
            today,
        )

        private val current = version(1, cp("1000.00", "10.00")).approve(senior, now, today, seniorApprover = true)

        @Test
        fun `raising any currency limit without the senior role is refused`() {
            assertThatThrownBy { version(2, cp("2000.00", "10.00")).approve(approver, now, today, false, current) }
                .isInstanceOf(SeniorApprovalRequiredException::class.java)
                .hasMessageContaining("CZK")
            assertThatThrownBy { version(2, cp("1000.00", "11.00")).approve(approver, now, today, false, current) }
                .isInstanceOf(SeniorApprovalRequiredException::class.java)
        }

        @Test
        fun `a new currency line is a raise from zero`() {
            assertThatThrownBy {
                version(
                    2,
                    cp("1000.00", "10.00").copy(
                        limits = mapOf(
                            "CZK" to java.math.BigDecimal("1000.00"),
                            "EUR" to java.math.BigDecimal("10.00"),
                            "USD" to java.math.BigDecimal("1"),
                        ),
                    ),
                ).approve(approver, now, today, false, current)
            }
                .isInstanceOf(SeniorApprovalRequiredException::class.java)
            assertThatThrownBy { version(1, cp("1.00")).approve(approver, now, today, false, null) }
                .isInstanceOf(SeniorApprovalRequiredException::class.java)
        }

        @Test
        fun `a senior may raise, and the limits before and after are recorded`() {
            val raised = version(2, cp("2000.00", "10.00")).approve(senior, now, today, true, current)
            assertThat(raised.approvalNote).contains("CZK 1000.00 -> 2000.00").contains("previous version 1")
        }

        @Test
        fun `a cut needs no senior and is still recorded`() {
            val cut = version(2, cp("500.00", "10.00")).approve(approver, now, today, false, current)
            assertThat(cut.status).isEqualTo(ReferenceStatus.ACTIVE)
            assertThat(cut.approvalNote).contains("CZK 1000.00 -> 500.00")
        }

        @Test
        fun `the senior is still not the proposer`() {
            val bySenior = ReferenceRecord.propose(
                ReferenceKind.COUNTERPARTY, "SIMBK-A", 2, today, null, cp("2000.00"), "r", senior, now, today,
            )
            assertThatThrownBy { bySenior.approve(senior, now, today, true, current) }
                .isInstanceOf(FourEyesViolationException::class.java)
        }

        @Test
        fun `counterparty and SSI versions may not be back-dated, at proposal or at approval`() {
            assertThatThrownBy {
                version(2, cp("1.00"), today.minusDays(1))
            }.isInstanceOf(ReferenceBackdatedException::class.java)
            assertThatThrownBy {
                ReferenceRecord.propose(
                    ReferenceKind.SSI, "SIMBK-A:CZK", 1, today.minusDays(1), null,
                    SsiPayload("SIMBK-A", "CZK", SettlementRail.CERTIS, "TEST-ACCOUNT-0001"), "r", editor, now, today,
                )
            }.isInstanceOf(ReferenceBackdatedException::class.java)
            val proposedYesterday = version(2, cp("900.00"), today)
            assertThatThrownBy { proposedYesterday.approve(approver, now, today.plusDays(1), false, current) }
                .isInstanceOf(ReferenceBackdatedException::class.java)
            // A calendar may be corrected retroactively (a missed closure); only the two kinds are guarded.
            proposed(from = "2020-01-01").approve(approver, now, today)
        }
    }
}
