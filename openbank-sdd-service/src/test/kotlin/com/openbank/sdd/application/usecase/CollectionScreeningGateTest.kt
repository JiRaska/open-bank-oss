// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sdd.application.usecase

import com.openbank.libs.fincrime.AmlCaseSeverity
import com.openbank.libs.fincrime.ScreeningMatchStatus
import com.openbank.libs.fincrime.ScreeningRole
import com.openbank.sdd.Fixtures
import com.openbank.sdd.domain.authorise.AuthorisationResult
import com.openbank.sdd.domain.authorise.CollectionInstruction
import com.openbank.sdd.domain.model.SddScheme
import com.openbank.sdd.domain.model.SequenceType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class CollectionScreeningGateTest {

    private val sanctions = FakeSanctions()
    private val aml = FakeAmlCases()
    private val gate = CollectionScreeningGate(sanctions, aml)
    private val instruction = CollectionInstruction(
        "DE98ZZZ09999999999",
        "UMR-0001",
        SddScheme.CORE,
        SequenceType.RCUR,
        BigDecimal.TEN,
        "EUR",
        LocalDate.parse("2026-03-01"),
    )

    private fun run(): AuthorisationResult? = gate.screen(Fixtures.mandate(), instruction).await().indefinitely()

    @Test
    fun `both parties clear releases the collection and opens no case`() {
        assertThat(run()).isNull()
        assertThat(
            sanctions.screened.map {
                it.second
            },
        ).containsExactlyInAnyOrder(ScreeningRole.DEBTOR, ScreeningRole.CREDITOR)
        assertThat(aml.opened).isEmpty()
    }

    @Test
    fun `a whitelisted party is clear`() {
        sanctions.statuses[ScreeningRole.DEBTOR] = ScreeningMatchStatus.WHITELISTED
        assertThat(run()).isNull()
    }

    @Test
    fun `a debtor HIT blocks with a CRITICAL case`() {
        sanctions.statuses[ScreeningRole.DEBTOR] = ScreeningMatchStatus.HIT
        assertThat(run()).isEqualTo(AuthorisationResult.Reject("RR04", "Collection blocked by sanctions screening"))
        assertThat(aml.opened.single().riskLevel).isEqualTo(AmlCaseSeverity.CRITICAL)
        assertThat(aml.opened.single().matchedEntity).isEqualTo("match-DEBTOR")
    }

    @Test
    fun `an ESCALATED status (the unknown-wire parse) blocks, never clears`() {
        sanctions.statuses[ScreeningRole.CREDITOR] = ScreeningMatchStatus.ESCALATED
        assertThat((run() as AuthorisationResult.Reject).reason).contains("blocked")
    }

    @Test
    fun `a low-score potential hit is held for review with a HIGH case`() {
        sanctions.statuses[ScreeningRole.CREDITOR] = ScreeningMatchStatus.POTENTIAL_HIT
        sanctions.scores[ScreeningRole.CREDITOR] = 0.7
        assertThat(run()).isEqualTo(AuthorisationResult.Reject("RR04", "Collection held for sanctions review"))
        assertThat(aml.opened.single().riskLevel).isEqualTo(AmlCaseSeverity.HIGH)
    }

    @Test
    fun `unavailable screening holds fail-closed with a MEDIUM case`() {
        sanctions.unavailable = true
        assertThat(
            run(),
        ).isEqualTo(AuthorisationResult.Reject("RR04", "Collection held: sanctions screening unavailable"))
        assertThat(aml.opened.single().riskLevel).isEqualTo(AmlCaseSeverity.MEDIUM)
    }

    @Test
    fun `an AML case-store outage never flips a block into a release`() {
        sanctions.statuses[ScreeningRole.DEBTOR] = ScreeningMatchStatus.HIT
        aml.fail = true
        assertThat(run()).isInstanceOf(AuthorisationResult.Reject::class.java)
    }

    @Test
    fun `a re-presented collection is screened under fresh keys, never a replayed stored answer`() {
        run()
        run()
        assertThat(sanctions.keys).hasSize(4).doesNotHaveDuplicates()
    }

    @Test
    fun `a worse later verdict opens its own AML case instead of colliding with the first key`() {
        sanctions.statuses[ScreeningRole.DEBTOR] = ScreeningMatchStatus.POTENTIAL_HIT
        sanctions.scores[ScreeningRole.DEBTOR] = 0.7
        run()
        sanctions.statuses[ScreeningRole.DEBTOR] = ScreeningMatchStatus.HIT
        run()
        assertThat(aml.opened.map { it.idempotencyKey }).hasSize(2).doesNotHaveDuplicates()
    }
}
