// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.ledger.infrastructure.rest

import com.openbank.libs.authz.Authorize
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The frozen statutory trial balance carries its OWN OPA action (ADR-0337, #12496), so a policy
 * can grant exactly that read — the pension company's ledger grants tax-reporting nothing else.
 * Reverting it to `ledger.read` would silently widen every such grant back to every ledger read;
 * the rego tests cannot see that, because they key on the action string this annotation emits.
 */
class ClosedPeriodAuthorizeActionTest {

    private fun actionOf(method: String): String = ClosedPeriodResource::class.java.declaredMethods
        .single { it.name == method }
        .getAnnotation(Authorize::class.java)
        .action

    @Test
    fun `the frozen trial balance has its own non-read action`() {
        assertThat(actionOf("frozenTrialBalance")).isEqualTo("ledger.close.inspect")
    }

    @Test
    fun `the computed trial balance stays a plain ledger read`() {
        assertThat(actionOf("trialBalance")).isEqualTo("ledger.read")
    }
}
