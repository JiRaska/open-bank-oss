// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.referral.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * ADR-0310 D1's eligibility rule. Each guard has a test that is red when that guard is removed —
 * the issue-time lower bound in particular, which is the anti-farming control and the one a
 * "simplification" would most plausibly delete.
 */
class QualificationRuleTest {
    private val issuedAt = Instant.parse("2026-09-01T10:00:00Z")
    private val referee = UUID.randomUUID()
    private val program = ReferralProgram(
        id = UUID.randomUUID(),
        name = "mgm",
        version = 1,
        rewardAmount = BigDecimal.TEN,
        currency = "CZK",
        qualifyingEvent = QualificationRule.ACCOUNT_OPENED,
        attributionWindowEndsAt = issuedAt.plus(Duration.ofDays(30)),
        status = ProgramStatus.PUBLISHED,
        maker = "maker",
        checker = "checker",
        createdAt = issuedAt.minus(Duration.ofDays(1)),
        publishedAt = issuedAt.minus(Duration.ofDays(1)),
    )
    private val invite = ReferralInvite(
        id = UUID.randomUUID(),
        programId = program.id,
        token = "hash",
        referrerPartyId = UUID.randomUUID(),
        refereePartyId = referee,
        status = InviteStatus.ATTRIBUTED,
        expiresAt = program.attributionWindowEndsAt,
        idempotencyKey = "k",
        attributedAt = issuedAt.plus(Duration.ofDays(2)),
    )

    private fun fact(occurredAt: Instant, party: UUID = referee, eventName: String = QualificationRule.ACCOUNT_OPENED) =
        QualifyingFact(UUID.randomUUID(), party, eventName, UUID.randomUUID().toString(), null, occurredAt, occurredAt)

    @Test
    fun `an account opened after the invite was issued and before it expired qualifies`() {
        val decision = QualificationRule.decide(program, invite, fact(issuedAt.plusSeconds(60)), issuedAt)
        assertThat(decision).isEqualTo(QualificationDecision.Eligible)
    }

    @Test
    fun `an account opened before the invite existed does not qualify`() {
        val decision = QualificationRule.decide(program, invite, fact(issuedAt.minusSeconds(1)), issuedAt)
        assertThat(decision).isEqualTo(QualificationDecision.Ineligible(IneligibilityReason.FACT_BEFORE_INVITE_ISSUED))
    }

    @Test
    fun `an account opened exactly at the invite expiry does not qualify`() {
        val decision = QualificationRule.decide(program, invite, fact(invite.expiresAt), issuedAt)
        assertThat(decision).isEqualTo(QualificationDecision.Ineligible(IneligibilityReason.FACT_AFTER_INVITE_EXPIRED))
    }

    @Test
    fun `an unknown issue instant fails closed`() {
        val decision = QualificationRule.decide(program, invite, fact(issuedAt.plusSeconds(60)), null)
        assertThat(decision).isEqualTo(QualificationDecision.Ineligible(IneligibilityReason.INVITE_ISSUE_TIME_UNKNOWN))
    }

    @Test
    fun `a fact for another party never qualifies the invite`() {
        val decision = QualificationRule.decide(
            program,
            invite,
            fact(issuedAt.plusSeconds(60), party = UUID.randomUUID()),
            issuedAt,
        )
        assertThat(decision).isEqualTo(QualificationDecision.Ineligible(IneligibilityReason.FACT_IS_FOR_ANOTHER_PARTY))
    }

    @Test
    fun `an invite that is not attributed does not qualify`() {
        val issued = invite.copy(status = InviteStatus.ISSUED, refereePartyId = null)
        val decision = QualificationRule.decide(program, issued, fact(issuedAt.plusSeconds(60)), issuedAt)
        assertThat(decision).isEqualTo(QualificationDecision.Ineligible(IneligibilityReason.INVITE_NOT_ATTRIBUTED))
    }

    @Test
    fun `a programme that qualifies on another event is not satisfied by account opened`() {
        val decision = QualificationRule.decide(
            program.copy(qualifyingEvent = "card.activated"),
            invite,
            fact(issuedAt.plusSeconds(60)),
            issuedAt,
        )
        assertThat(decision).isEqualTo(QualificationDecision.Ineligible(IneligibilityReason.EVENT_DOES_NOT_QUALIFY))
    }

    @Test
    fun `an unpublished programme does not qualify`() {
        val decision = QualificationRule.decide(
            program.copy(status = ProgramStatus.EXPIRED),
            invite,
            fact(issuedAt.plusSeconds(60)),
            issuedAt,
        )
        assertThat(decision).isEqualTo(QualificationDecision.Ineligible(IneligibilityReason.PROGRAM_NOT_PUBLISHED))
    }

    @Test
    fun `only AccountCreated translates to account opened`() {
        assertThat(QualificationRule.qualifyingKeyFor("AccountCreated")).isEqualTo(QualificationRule.ACCOUNT_OPENED)
        assertThat(QualificationRule.qualifyingKeyFor("AccountStatusChanged")).isNull()
        assertThat(QualificationRule.qualifyingKeyFor("account.opened")).isNull()
    }
}
