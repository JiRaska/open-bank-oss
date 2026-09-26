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
import com.openbank.risk.domain.model.PositionKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PositionBuilderTest {

    @Test
    fun `one position per sub-ledger balance on the control account of its currency, in debit-minus-credit sign`() {
        val positions = PositionBuilder.build(Fixtures.tiedOut())

        val subs = positions.filter { it.kind == PositionKind.SUB_LEDGER }
        assertThat(subs).hasSize(2)
        val alice = subs.single { it.subAccountId == ALICE }
        assertThat(alice.glAccountCode).isEqualTo("2100")
        assertThat(alice.glAccountType).isEqualTo("LIABILITY")
        // A customer credit balance of 1000 is -1000 in the trial-balance convention.
        assertThat(alice.amount).isEqualByComparingTo("-1000.00")
        assertThat(subs.single { it.subAccountId == BOB }.amount).isEqualByComparingTo("-500.00")
    }

    @Test
    fun `a GL account without a sub-ledger breakdown becomes one GL-level position`() {
        val positions = PositionBuilder.build(Fixtures.tiedOut())

        val gl = positions.filter { it.kind == PositionKind.GL_ACCOUNT }
        assertThat(gl).hasSize(1)
        assertThat(gl.single().glAccountCode).isEqualTo("1001")
        assertThat(gl.single().subAccountId).isNull()
        assertThat(gl.single().amount).isEqualByComparingTo("1500.00")
    }

    @Test
    fun `a deposit-control account is never turned into a GL-level position, even with no sub-ledger rows`() {
        val inputs = LedgerInputs(AS_OF, listOf(tb("2100", "LIABILITY", "CZK", "0", "700")), emptyList())

        assertThat(PositionBuilder.build(inputs)).isEmpty()
    }

    @Test
    fun `a sub-ledger balance whose currency has no control account is unmapped`() {
        val inputs = LedgerInputs(AS_OF, emptyList(), listOf(sl(ALICE, "USD", "0", "10")))

        val position = PositionBuilder.build(inputs).single()
        assertThat(position.glAccountCode).isNull()
        assertThat(position.amount).isEqualByComparingTo(BigDecimal("-10"))
    }
}
