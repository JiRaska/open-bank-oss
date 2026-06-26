// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.sdd.domain.authorise

import com.openbank.sdd.Fixtures
import com.openbank.sdd.domain.model.MandateStatus
import com.openbank.sdd.domain.model.SddScheme
import com.openbank.sdd.domain.model.SequenceType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class CollectionAuthorisationPolicyTest {

    private fun instruction(
        scheme: SddScheme = SddScheme.CORE,
        amount: BigDecimal = BigDecimal("42.00"),
        currency: String = "EUR",
    ) = CollectionInstruction(
        creditorIdentifier = "DE98ZZZ09999999999",
        umr = "UMR-0001",
        scheme = scheme,
        sequenceType = SequenceType.RCUR,
        amount = amount,
        currency = currency,
        dueDate = LocalDate.parse("2026-03-01"),
    )

    @Test
    fun `an active matching mandate within controls is accepted`() {
        val r = CollectionAuthorisationPolicy.authorise(Fixtures.mandate(), instruction(), DebtorControls())
        assertThat(r).isInstanceOf(AuthorisationResult.Accept::class.java)
    }

    @Test
    fun `no mandate is rejected with MD01`() {
        val r = CollectionAuthorisationPolicy.authorise(null, instruction(), DebtorControls())
        assertThat(r).isInstanceOf(AuthorisationResult.Reject::class.java)
        assertThat((r as AuthorisationResult.Reject).reasonCode).isEqualTo("MD01")
    }

    @Test
    fun `a non-active mandate is rejected`() {
        val r = CollectionAuthorisationPolicy.authorise(
            Fixtures.mandate(status = MandateStatus.SUSPENDED), instruction(), DebtorControls(),
        )
        assertThat(r).isInstanceOf(AuthorisationResult.Reject::class.java)
    }

    @Test
    fun `a scheme mismatch is rejected`() {
        val r = CollectionAuthorisationPolicy.authorise(
            Fixtures.mandate(scheme = SddScheme.CORE), instruction(scheme = SddScheme.B2B), DebtorControls(),
        )
        assertThat(r).isInstanceOf(AuthorisationResult.Reject::class.java)
    }

    @Test
    fun `a non-EUR collection is rejected because SDD is EUR-only`() {
        val r = CollectionAuthorisationPolicy.authorise(Fixtures.mandate(), instruction(currency = "CZK"), DebtorControls())
        assertThat(r).isInstanceOf(AuthorisationResult.Reject::class.java)
        assertThat((r as AuthorisationResult.Reject).reasonCode).isEqualTo("FF05")
    }

    @Test
    fun `an unconfirmed B2B mandate is rejected`() {
        val mandate = Fixtures.mandate(scheme = SddScheme.B2B, b2bConfirmed = false)
        val r = CollectionAuthorisationPolicy.authorise(mandate, instruction(scheme = SddScheme.B2B), DebtorControls())
        assertThat(r).isInstanceOf(AuthorisationResult.Reject::class.java)
    }

    @Test
    fun `a one-off mandate already used is rejected`() {
        val used = Fixtures.mandate(sequenceType = SequenceType.OOFF, lastCollectionDate = LocalDate.parse("2026-02-01"))
        val r = CollectionAuthorisationPolicy.authorise(used, instruction().copy(sequenceType = SequenceType.OOFF), DebtorControls())
        assertThat(r).isInstanceOf(AuthorisationResult.Reject::class.java)
    }

    @Test
    fun `block-all is a debtor refusal, not a reject`() {
        val r = CollectionAuthorisationPolicy.authorise(Fixtures.mandate(), instruction(), DebtorControls(blockAll = true))
        assertThat(r).isInstanceOf(AuthorisationResult.Refuse::class.java)
        assertThat((r as AuthorisationResult.Refuse).reasonCode).isEqualTo("MS02")
    }

    @Test
    fun `a block-listed creditor is refused`() {
        val r = CollectionAuthorisationPolicy.authorise(
            Fixtures.mandate(),
            instruction(),
            DebtorControls(blockedCreditors = setOf("DE98ZZZ09999999999")),
        )
        assertThat(r).isInstanceOf(AuthorisationResult.Refuse::class.java)
    }

    @Test
    fun `an amount above the debtor cap is refused`() {
        val r = CollectionAuthorisationPolicy.authorise(
            Fixtures.mandate(),
            instruction(amount = BigDecimal("500.00")),
            DebtorControls(maxAmountPerCollection = BigDecimal("100.00")),
        )
        assertThat(r).isInstanceOf(AuthorisationResult.Refuse::class.java)
    }
}
