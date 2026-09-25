// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ContextApiContractTest {
    private val contract = requireNotNull(javaClass.getResource("/openapi.yaml")).readText()

    @Test
    fun `Lending access contract returns only a case scoped decision`() {
        val operation = contract.substringAfter("/api/v1/context/lending-loans/{loanId}/access:")
            .substringBefore("/api/v1/context/lending-loans/{loanId}/approved-guarantees:")
        assertThat(operation).contains("verifyAssignedLendingLoanAccess", "'204'", "'403'", "'503'")
        assertThat(operation).doesNotContain("'200'", "guarantorPartyId", "capAmount", "sourceDocumentId")
    }

    @Test
    fun `Lending source evidence contract is bounded to one loan`() {
        val operation = contract.substringAfter("/api/v1/context/lending-loans/{loanId}/approved-guarantees:")
            .substringBefore("/api/v1/context/fraud-cases/{caseId}/network:")
        assertThat(operation).contains(
            "getAssignedLendingApprovedGuarantees",
            "LendingGuaranteeHistory",
            "'403'",
            "'503'",
        )
        assertThat(contract).contains("maxItems: 100", "sourceDocumentId", "sourceSha256")
    }

    @Test
    fun `Lending candidate contract returns only bounded assigned loan IDs`() {
        val operation = contract.substringAfter("/api/v1/context/lending-loans/{loanId}/assigned-candidates:")
            .substringBefore("/api/v1/context/fraud-cases/{caseId}/network:")
        assertThat(operation).contains(
            "getAssignedLendingCandidates",
            "LendingAssignedCandidates",
            "'200'",
            "'403'",
            "'503'",
        )
        assertThat(operation).doesNotContain("guarantorPartyId", "capAmount", "sourceDocumentId")
        val schema = contract.substringAfter("    LendingAssignedCandidates:")
            .substringBefore("    LendingGuaranteeHistory:")
        assertThat(schema).contains("maxItems: 256", "uniqueItems: true", "truncated:")
    }

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

    @Test
    fun `fraud source access contract exposes only a case scoped decision`() {
        val operation = contract.substringAfter("/api/v1/context/fraud-cases/{caseId}/access:")
            .substringBefore("/api/v1/context/fraud-cases/{caseId}/assigned-candidates:")
        assertThat(operation).contains("verifyAssignedFraudCaseAccess", "'204'", "'403'", "'503'")
        assertThat(operation).doesNotContain("accountId", "counterpartyId", "score", "amount", "reason")
    }

    @Test
    fun `Fraud candidate set is bounded and tied to the authorized human root`() {
        val operation = contract.substringAfter("/api/v1/context/fraud-cases/{caseId}/assigned-candidates:")
            .substringBefore("/api/v1/context/kyb-cases/{id}/ownership-observations:")
        assertThat(operation).contains("getAssignedFraudCandidates", "FraudAssignedCandidates", "'403'", "'503'")
        assertThat(contract).contains("maxItems: 256", "uniqueItems: true")
    }

    @Test
    fun `fraud network contract is bounded and labels incomplete candidate coverage`() {
        val operation = contract.substringAfter("/api/v1/context/fraud-cases/{caseId}/network:")
            .substringBefore("/api/v1/context/fraud-cases/{caseId}/access:")
        assertThat(operation).contains("getAssignedFraudCaseNetwork", "FraudCaseNetwork", "'403'", "'503'")
        assertThat(contract).contains("candidateTruncated:", "inspectedCandidates:", "maxItems: 4")
    }
}
