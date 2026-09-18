// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class ContextDisclosureSummariesTest {
    @Test
    fun `fraud summary records every identifier released in the source snapshot`() {
        val caseId = UUID.randomUUID()
        val scoreId = UUID.randomUUID()
        val accountId = UUID.randomUUID()
        val counterpartyId = UUID.randomUUID()
        val snapshot = FraudCaseSourceSnapshot(caseId, scoreId, accountId, counterpartyId, "OPEN", 3)
        val response = Response.ok(FraudCaseNetwork(snapshot, emptyList(), 0, false)).build()

        val summary = ContextDisclosureSummaries.response(response)

        assertThat(summary.evidenceRefs).containsExactlyInAnyOrder(
            "fraud-case:$caseId",
            "score:$scoreId",
            "account:$accountId",
            "counterparty:$counterpartyId",
        )
        assertThat(summary.evidenceCount).isEqualTo(4)
        assertThat(summary.projectionGeneration).matches("[0-9a-f]{64}")
    }

    @Test
    fun `unrecognized successful response cannot escape without a disclosure inventory`() {
        val response = Response.ok(mapOf("unexpected" to "evidence")).build()

        assertThatThrownBy { ContextDisclosureSummaries.response(response) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No disclosure audit summary")
    }
}
