// SPDX-License-Identifier: Apache-2.0
package com.openbank.fraud.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FraudEvidenceApiContractTest {
    @Test
    fun `status route declares case-scoped authorization and fail-closed response`() {
        val contract = requireNotNull(javaClass.getResource("/openapi.yaml")).readText()
        val operation = contract.substringAfter("/api/v1/fraud/cases/{caseId}:")
            .substringBefore("/api/v1/fraud/cases/{caseId}/evidence:")
        assertThat(operation).contains(
            "getFraudInvestigationCase",
            "live case-scoped Context authorization",
            "InvestigationPurpose",
            "'403'",
            "'503'",
        )
    }

    @Test
    fun `case closure declares live assignment and unavailable authorization response`() {
        val contract = requireNotNull(javaClass.getResource("/openapi.yaml")).readText()
        val operation = contract.substringAfter("/api/v1/fraud/cases/{caseId}/close-without-finding:")
            .substringBefore("/api/v1/fraud/review-queue:")
        assertThat(operation).contains(
            "closeFraudInvestigationCaseWithoutFinding",
            "Close an assigned case",
            "InvestigationPurpose",
            "'403'",
            "'503'",
        )
    }

    @Test
    fun `restricted evidence route declares source associations and fail closed responses`() {
        val contract = requireNotNull(javaClass.getResource("/openapi.yaml")).readText()
        val operation = contract.substringAfter("/api/v1/fraud/cases/{caseId}/evidence:")
            .substringBefore("/api/v1/fraud/cases/{caseId}/match-assigned:")
        assertThat(operation).contains(
            "getFraudInvestigationEvidence",
            "InvestigationPurpose",
            "FraudInvestigationEvidence",
            "'403'",
            "'503'",
        )
        assertThat(contract).contains("accountId:", "counterpartyId:")
    }

    @Test
    fun `source matching fetches assigned IDs independently and returns IDs only`() {
        val contract = requireNotNull(javaClass.getResource("/openapi.yaml")).readText()
        val operation = contract.substringAfter("/api/v1/fraud/cases/{caseId}/match-assigned:")
            .substringBefore("/api/v1/fraud/cases/{caseId}/close-without-finding:")
        assertThat(operation).contains(
            "get:",
            "matchAssignedFraudCases",
            "the caller cannot choose candidate IDs",
            "FraudAssignedMatchResponse",
            "InvestigationPurpose",
            "InvestigatorAuthorization",
            "'403'",
            "'503'",
        )
        assertThat(operation).doesNotContain("post:", "requestBody:")
        val response = contract.substringAfter("    FraudAssignedMatchResponse:")
            .substringBefore("    FraudInvestigationCase:")
        assertThat(response).contains("candidateIds", "maxItems: 4", "inspectedCandidates", "truncated")
            .doesNotContain("accountId", "counterpartyId", "scoreId")
    }
}
