// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DelegatedPaymentApprovalDecisionTest {
    private val mapper = ObjectMapper()

    @Test
    fun `additive approval flag refuses a direct debit and preserves the audit distinction`() {
        val node = mapper.readTree("""{"authorized":false,"outcome":"NO_GRANT","approvalRequired":true}""")
        val decision = parseDelegatedPaymentDecision(node)
        assertThat(decision.authorized).isFalse()
        assertThat(decision.outcome).isEqualTo("APPROVAL_REQUIRED")
        assertThat(decision.delegationId).isNull()
        assertThat(decision.grantorPartyId).isNull()
    }

    @Test
    fun `a contradictory approval-required response still fails closed`() {
        val node = mapper.readTree("""{"authorized":true,"outcome":"DELEGATED","approvalRequired":true}""")
        assertThat(parseDelegatedPaymentDecision(node).authorized).isFalse()
    }

    @Test
    fun `old provider responses remain readable during rolling deployment`() {
        val node = mapper.readTree("""{"authorized":false,"outcome":"NO_GRANT"}""")
        val decision = parseDelegatedPaymentDecision(node)
        assertThat(decision.authorized).isFalse()
        assertThat(decision.outcome).isEqualTo("NO_GRANT")
    }
}
