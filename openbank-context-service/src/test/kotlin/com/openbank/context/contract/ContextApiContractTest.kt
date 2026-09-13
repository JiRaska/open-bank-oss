// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ContextApiContractTest {
    private val contract = requireNotNull(javaClass.getResource("/openapi.yaml")).readText()

    @Test
    fun `contract exposes only the bounded P1 read lenses`() {
        assertThat(contract).contains(
            "/api/v1/context/complaints/{reference}",
            "/api/v1/context/incidents/{reference}/impact",
            "X-Investigation-Case-Id",
            "X-Investigation-Purpose",
            "'403'",
            "'503'",
        )
        assertThat(contract).doesNotContain("queryLanguage", "cypher", "drilldownIds")
    }
}
