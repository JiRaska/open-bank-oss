// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.integration

import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Keeps the outbox rows this test writes owned by the test, not by the background dispatcher:
 * `markSent`/`markFailed` issue an UPDATE, and an UPDATE rewrites the row's `xmin` — the very
 * value [ScaEnrollOutboxAtomicityIT] reads as its oracle.
 *
 * A `QuarkusTestProfile`, deliberately NOT a `@QuarkusTestResource`: a test resource applies to
 * every test class in the module, so `dispatch-enabled=false` there would silently turn
 * `ScaOutboxDispatcher.dispatch()` into a no-op for any current or future dispatch IT — measured
 * and corrected in #8676.
 */
class OutboxDispatchDisabledProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf("openbank.outbox.dispatch-enabled" to "false")
}
