// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class FraudCaseNetworkTest {
    @Test
    fun `same-role source ids link cases while cross-role coincidence does not`() {
        val account = UUID.randomUUID()
        val counterparty = UUID.randomUUID()
        val root = FraudCaseSourceSnapshot(accountId = account, counterpartyId = counterparty)

        val matching = FraudCaseSourceSnapshot(accountId = account, counterpartyId = counterparty)
        assertThat(sharedFraudReferences(root, matching).map { it.type })
            .containsExactly("ACCOUNT", "COUNTERPARTY")

        val crossed = FraudCaseSourceSnapshot(accountId = counterparty, counterpartyId = account)
        assertThat(sharedFraudReferences(root, crossed)).isEmpty()
    }
}
