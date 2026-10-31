// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure

import com.openbank.productcatalog.infrastructure.catalog.ClasspathApprovalPortfolios
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The approval manifest is refused at BOOT rather than misread at the first approval (ADR-0352 §7): a
 * section other than `byProductCode` (above all a currency fallback), two entries naming one offering,
 * a file that is missing, or a name that is not a plain file name.
 */
class ClasspathApprovalPortfoliosTest {

    private fun load(files: Map<String, String>) = ClasspathApprovalPortfolios { name ->
        files[name]?.toByteArray()
            ?: error("approval-portfolios/$name is missing")
    }

    private fun manifest(sections: String) =
        """{"schema":"openbank.approval-portfolios.v1","description":"t",$sections}"""

    private val portfolio = """{"accounts":[]}"""

    @Test
    fun `a well-formed manifest resolves an offering code by its IR product code`() {
        val source = load(
            mapOf(
                "approval.json" to manifest(""""byProductCode":{"SAVINGS-FIXED":"a.json"}"""),
                "a.json" to portfolio,
            ),
        )
        assertThat(source.find("SAVINGS_FIXED")?.name).isEqualTo("a.json")
        assertThat(source.find("SAVINGS-FIXED")).isNull()
        assertThat(source.find("CURRENT_ACCOUNT")).isNull()
    }

    @Test
    fun `a currency fallback section refuses the boot`() {
        assertThatThrownBy {
            load(
                mapOf(
                    "approval.json" to manifest(""""byProductCode":{},"byCurrency":{"EUR":"a.json"}"""),
                    "a.json" to portfolio,
                ),
            )
        }.hasMessageContaining("must carry exactly")
    }

    @Test
    fun `two entries naming the same offering refuse the boot`() {
        assertThatThrownBy {
            load(
                mapOf(
                    "approval.json" to
                        manifest(""""byProductCode":{"SAVINGS-FIXED":"a.json","SAVINGS_FIXED":"b.json"}"""),
                    "a.json" to portfolio,
                    "b.json" to portfolio,
                ),
            )
        }.hasMessageContaining("names offering SAVINGS_FIXED twice")
    }

    @Test
    fun `a missing portfolio file or a non-plain name refuses the boot`() {
        assertThatThrownBy {
            load(mapOf("approval.json" to manifest(""""byProductCode":{"SAVINGS-FIXED":"gone.json"}""")))
        }.hasMessageContaining("gone.json is missing")
        assertThatThrownBy {
            load(mapOf("approval.json" to manifest(""""byProductCode":{"SAVINGS-FIXED":"../secret.json"}""")))
        }.hasMessageContaining("is not a plain file name")
    }
}
