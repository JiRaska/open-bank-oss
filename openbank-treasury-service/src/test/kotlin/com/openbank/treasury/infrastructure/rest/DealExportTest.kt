// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.rest

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DealExportTest {
    private fun cell(v: String) = DealExport.csv(listOf(mapOf("x" to v))).lines()[1]

    @Test
    fun `a value a spreadsheet would read as a formula is neutralised, leading blanks and controls included`() {
        listOf("=1+1", "+1", "-1", "@SUM(A1)", "\t=1", "\r=1", "\n=1", "  =1", "\u0007=1").forEach {
            assertThat(cell(it)).describedAs(it.replace("\t", "\\t")).startsWith("\"'")
        }
        assertThat(cell("plain")).isEqualTo("\"plain\"")
        assertThat(cell("a\"b")).isEqualTo("\"a\"\"b\"")
    }
}
