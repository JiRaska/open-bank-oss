// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.application.usecase

import com.openbank.sca.domain.model.ScaPurpose
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The static push-text table replaced an exhaustive `when`; this restores that exhaustiveness. */
class PushMessageCoverageTest {

    @Test
    fun `every purpose without dynamic push text has a static message`() {
        val dynamic = setOf(ScaPurpose.PAYMENT_INITIATION, ScaPurpose.APPROVAL)
        assertThat(STATIC_PUSH_MESSAGES.keys).isEqualTo(ScaPurpose.entries.toSet() - dynamic)
    }
}
