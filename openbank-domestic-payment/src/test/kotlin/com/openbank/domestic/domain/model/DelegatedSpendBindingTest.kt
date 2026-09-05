// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DelegatedSpendBindingTest {
    @Test
    fun `idempotency key hash is byte-identical to the producer test vector`() {
        assertThat(DelegatedSpendReservationSnapshot.hashIdempotencyKey("payment-42")).isEqualTo(
            "d5fcf99c283a194aff198754caa138862271e9f046af15e706ee317058ba9aad",
        )
    }
}
