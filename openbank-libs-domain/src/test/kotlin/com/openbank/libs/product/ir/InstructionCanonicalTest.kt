// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.product.ir

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class InstructionCanonicalTest {

    @Test
    fun `writes every instruction kind with sorted keys and fixed-scale amounts`() {
        val all = listOf(
            Instruction.Post(
                "a",
                "h",
                1,
                listOf(PostingLine(AccountingClass.FEE_INCOME, PostingDirection.CREDIT, BigDecimal("1.5"), "EUR")),
                "n",
            ),
            Instruction.Reject("b", "h", 1, "X"),
            Instruction.Schedule("c", "h", 1, Hook.SCHEDULED, "T", Cadence.AT_MATURITY, IrFixtures.day),
            Instruction.Schedule("c2", "h", 1, Hook.ON_CLOSE, "T", Cadence.DAILY, null),
            Instruction.Notify("d", "h", 1, "tpl", mapOf("k" to "v")),
            Instruction.Reprice("e", "h", 1, "interest.rate", BigDecimal("0.03")),
        )
        val json = InstructionCanonical.write(all)
        assertThat(
            json,
        ).startsWith("[{\"irVersion\":1,\"lines\":[{\"accountingClass\":\"FEE_INCOME\",\"amount\":\"1.500000000000\"")
        assertThat(
            json,
        ).contains(
            "\"type\":\"REJECT\"",
            "\"at\":\"2026-03-15\"",
            "\"hook\":\"onClose\"",
            "\"at\":null",
            "\"value\":\"0.0300000000\"",
        )
        assertThat(json).isEqualTo(InstructionCanonical.write(all))
    }
}
