// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.anacredit.e2e

import com.openbank.libs.testing.trace.TraceContractProfile

/**
 * The E2E trace boot: the recorder is an application-scoped bean, so without a profile distinct
 * from [com.openbank.anacredit.integration.AnaCreditTraceContractIT]'s the two classes deposit
 * their spans in the same recorder and each class's `requiresSameTrace` would read the other's
 * traffic. Isolation is the point, not the settings — only literals here (a profile loads in a
 * different classloader from the test class).
 */
class AnaCreditE2eTraceProfile : TraceContractProfile() {
    override val disableOutboxDispatcher: Boolean = false

    override fun extraConfigOverrides(): Map<String, String> = mapOf(
        "authz.enforce" to "false",
    )
}
