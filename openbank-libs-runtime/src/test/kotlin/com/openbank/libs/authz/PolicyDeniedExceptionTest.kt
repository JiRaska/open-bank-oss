// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.authz

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [PolicyDeniedException.reason] is the PDP's explanation, carried for the log and the trace —
 * an empty reason here would render every enforced denial indistinguishable in telemetry.
 */
class PolicyDeniedExceptionTest {

    @Test
    fun `the denial reason is carried on the exception`() {
        val denied = PolicyDeniedException("insufficient role")
        assertThat(denied.reason).isEqualTo("insufficient role")
        assertThat(denied.message).isEqualTo("insufficient role")
    }
}
