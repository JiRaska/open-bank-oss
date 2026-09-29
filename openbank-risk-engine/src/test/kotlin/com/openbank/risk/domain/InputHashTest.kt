// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain

import com.openbank.risk.domain.Fixtures.ALICE
import com.openbank.risk.domain.Fixtures.sl
import com.openbank.risk.domain.model.InputHash
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InputHashTest {

    @Test
    fun `the hash is independent of the order the ledger returned its lines in`() {
        val inputs = Fixtures.tiedOut()
        val shuffled = inputs.copy(
            trialBalance = inputs.trialBalance.reversed(),
            subLedger = inputs.subLedger.reversed(),
        )

        assertThat(InputHash.of(shuffled)).isEqualTo(InputHash.of(inputs))
    }

    @Test
    fun `the hash is independent of amount scale`() {
        val inputs = Fixtures.tiedOut()
        val rescaled = inputs.copy(
            subLedger = inputs.subLedger.map {
                it.copy(totalCredit = it.totalCredit.setScale(4))
            },
        )

        assertThat(InputHash.of(rescaled)).isEqualTo(InputHash.of(inputs))
    }

    @Test
    fun `changing any single amount changes the hash`() {
        val inputs = Fixtures.tiedOut()
        val base = InputHash.of(inputs)

        val subChanged = inputs.copy(subLedger = listOf(sl(ALICE, "CZK", "0", "1000.01")) + inputs.subLedger.drop(1))
        val tbChanged = inputs.copy(
            trialBalance = listOf(inputs.trialBalance.first().copy(net = inputs.trialBalance.first().net.negate())) +
                inputs.trialBalance.drop(1),
        )

        assertThat(InputHash.of(subChanged)).isNotEqualTo(base)
        assertThat(InputHash.of(tbChanged)).isNotEqualTo(base)
    }

    @Test
    fun `the as-of date is part of the hash, and the hash is lowercase SHA-256 hex`() {
        val inputs = Fixtures.tiedOut()

        assertThat(InputHash.of(inputs.copy(asOf = inputs.asOf.plusDays(1)))).isNotEqualTo(InputHash.of(inputs))
        assertThat(InputHash.of(inputs)).matches("[0-9a-f]{64}")
    }
}
