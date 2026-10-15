// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The one piece of [PanacheOutboxRetention] that runs without a Hibernate session: the
 * `retentionLabel` derivation, which fleet dashboards and the per-service retention wiring key on.
 */
class PanacheOutboxRetentionTest {

    @Test
    fun `the retention label is the table name minus its outbox suffix, dasherised`() {
        assertThat(PanacheOutboxRetention(OutboxTableShape("sca_outbox")).retentionLabel)
            .isEqualTo("sca")
        assertThat(PanacheOutboxRetention(OutboxTableShape("sepa_payment_outbox")).retentionLabel)
            .isEqualTo("sepa-payment")
    }

    @Test
    fun `a plain retention delegate is not exempt from the enforced retention gate`() {
        // The interface default is the behaviour under test: only a reasoned, per-service override
        // may claim an exemption.
        assertThat(PanacheOutboxRetention(OutboxTableShape("sca_outbox")).sentRetentionExempt).isFalse()
    }
}
