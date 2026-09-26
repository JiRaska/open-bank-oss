// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.authz

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #10486: the per-request INFO line naming the machine identity behind an enforced decision.
 * It must name a service account and must never name anyone else — a human's or an agent's
 * subject id is not logged at INFO by this line.
 */
class M2mDecisionLineTest {

    @Test
    fun `a service-account allow names action, principal and the identity rule that fired`() {
        assertThat(
            m2mDecisionLine(
                "ledger.create",
                "service-account-openbank-lending",
                "allow",
                "service-lending-ledger-post",
            ),
        ).isEqualTo(
            "authz m2m decision: outcome=allow action=ledger.create " +
                "principal=service-account-openbank-lending reason=service-lending-ledger-post",
        )
    }

    @Test
    fun `a service-account deny is logged too, with an unspecified reason when OPA gave none`() {
        assertThat(m2mDecisionLine("transaction.create", "service-account-openbank-mcp-service", "deny", null))
            .isEqualTo(
                "authz m2m decision: outcome=deny action=transaction.create " +
                    "principal=service-account-openbank-mcp-service reason=unspecified",
            )
    }

    @Test
    fun `a human or agent principal produces no line at all`() {
        listOf("6f1c2a52-7d0e-4c3e-9a11-0b7d2b5e8c41", "agent:ui-assistant", "u-op", "").forEach { id ->
            assertThat(m2mDecisionLine("ledger.create", id, "allow", "operator-ledger-write"))
                .describedAs("principal %s", id)
                .isNull()
        }
    }
}
