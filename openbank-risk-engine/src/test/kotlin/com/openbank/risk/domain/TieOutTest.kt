// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain

import com.openbank.risk.domain.Fixtures.ALICE
import com.openbank.risk.domain.Fixtures.AS_OF
import com.openbank.risk.domain.Fixtures.BOB
import com.openbank.risk.domain.Fixtures.sl
import com.openbank.risk.domain.Fixtures.tb
import com.openbank.risk.domain.model.LedgerInputs
import com.openbank.risk.domain.model.PositionBuilder
import com.openbank.risk.domain.model.TieOut
import com.openbank.risk.domain.model.TieOutStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TieOutTest {

    private fun tieOut(inputs: LedgerInputs) = TieOut.check(inputs.trialBalance, PositionBuilder.build(inputs))

    @Test
    fun `sub-ledger positions summing to the control account tie out`() {
        val result = tieOut(Fixtures.tiedOut())

        assertThat(result.status).isEqualTo(TieOutStatus.TIED_OUT)
        assertThat(result.mismatches).isEmpty()
    }

    @Test
    fun `scale differences alone are not a break`() {
        val inputs = LedgerInputs(
            AS_OF,
            listOf(tb("2100", "LIABILITY", "CZK", "0", "1500.0000")),
            listOf(sl(ALICE, "CZK", "0", "1000"), sl(BOB, "CZK", "0", "500.00")),
        )

        assertThat(tieOut(inputs).status).isEqualTo(TieOutStatus.TIED_OUT)
    }

    @Test
    fun `a sub-ledger one cent short of the control account is untied, with the exact difference`() {
        val inputs = Fixtures.tiedOut().copy(
            subLedger = listOf(sl(ALICE, "CZK", "0", "1000.00"), sl(BOB, "CZK", "0", "499.99")),
        )

        val result = tieOut(inputs)

        assertThat(result.status).isEqualTo(TieOutStatus.UNTIED)
        val mismatch = result.mismatches.single()
        assertThat(mismatch.glAccountCode).isEqualTo("2100")
        assertThat(mismatch.currency).isEqualTo("CZK")
        assertThat(mismatch.ledgerNet).isEqualByComparingTo("-1500.00")
        assertThat(mismatch.positionsNet).isEqualByComparingTo("-1499.99")
        assertThat(mismatch.difference).isEqualByComparingTo("0.01")
    }

    @Test
    fun `a control account with a balance and no sub-ledger breakdown is untied, not tied by its own GL figure`() {
        val inputs = LedgerInputs(
            AS_OF,
            listOf(tb("1001", "ASSET", "EUR", "200", "0"), tb("2101", "LIABILITY", "EUR", "0", "200")),
            emptyList(),
        )

        val result = tieOut(inputs)

        assertThat(result.status).isEqualTo(TieOutStatus.UNTIED)
        assertThat(result.mismatches.single().glAccountCode).isEqualTo("2101")
        assertThat(result.mismatches.single().positionsNet).isEqualByComparingTo("0")
    }

    @Test
    fun `a sub-ledger currency missing from the trial balance is an unmapped break`() {
        val inputs = Fixtures.tiedOut().copy(subLedger = Fixtures.tiedOut().subLedger + sl(BOB, "USD", "0", "5"))

        val result = tieOut(inputs)

        assertThat(result.status).isEqualTo(TieOutStatus.UNTIED)
        val mismatch = result.mismatches.single()
        assertThat(mismatch.glAccountCode).isNull()
        assertThat(mismatch.currency).isEqualTo("USD")
        assertThat(mismatch.positionsNet).isEqualByComparingTo("-5")
    }

    @Test
    fun `an unmapped position is a break even when it nets to zero`() {
        val inputs = Fixtures.tiedOut().copy(subLedger = Fixtures.tiedOut().subLedger + sl(BOB, "USD", "5", "5"))

        assertThat(tieOut(inputs).status).isEqualTo(TieOutStatus.UNTIED)
    }

    @Test
    fun `a GL account without sub-ledger breakdown ties out on its own line`() {
        val inputs = LedgerInputs(AS_OF, listOf(tb("6000", "EQUITY", "CZK", "0", "1000000")), emptyList())

        assertThat(tieOut(inputs).status).isEqualTo(TieOutStatus.TIED_OUT)
    }
}
