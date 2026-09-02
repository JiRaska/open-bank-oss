// SPDX-License-Identifier: Apache-2.0
package com.openbank.delegation.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DelegationLifecycleApprovalOpenApiTest {
    @Test
    fun `contract publishes read list detail and dark launched mutation semantics`() {
        val contract = requireNotNull(javaClass.getResource("/openapi.yaml")).readText()

        assertThat(contract).contains("/api/v1/delegations/approvals:")
        assertThat(contract).contains("/api/v1/delegations/approvals/{approvalId}:")
        assertThat(contract).contains("/api/v1/delegations/approvals/{approvalId}/decision:")
        assertThat(contract).contains("DelegationLifecycleApprovalResponse:")
        assertThat(contract).contains("mutations-enabled")
        assertThat(contract).contains("approve=true is refused with 409")
        assertThat(contract).contains("revision/CAS seam")
    }
}
