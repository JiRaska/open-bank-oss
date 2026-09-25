// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ContextApiContractTest {
    private val contract = requireNotNull(javaClass.getResource("/openapi.yaml")).readText()

    @Test
    fun `KYB source access check has a data-free success response`() {
        val operation = contract.substringAfter("/api/v1/context/kyb-cases/{id}/access:")
            .substringBefore("/api/v1/context/kyb-cases/{id}/ownership-observations:")
        assertThat(operation).contains(
            "checkAssignedKybCaseAccess",
            "'204'",
            "'403'",
            "'503'",
        )
        assertThat(operation).doesNotContain("'200'", "KybObservationHistory")
    }

    @Test
    fun `contract exposes bounded lenses and controlled assignment lifecycle`() {
        assertThat(contract).contains(
            "/api/v1/context/complaints/{reference}",
            "/api/v1/context/incidents/{reference}/impact",
            "X-Investigation-Case-Id",
            "X-Investigation-Purpose",
            "/api/v1/context/assignment-proposals",
            "proposeContextAssignment",
            "decideContextAssignment",
            "revokeContextAssignment",
            "listActiveContextAssignments",
            "'409'",
            "'403'",
            "'503'",
        )
        assertThat(contract).doesNotContain("bankScope", "queryLanguage", "cypher", "drilldownIds")
    }
}
