// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent

import com.openbank.consent.infrastructure.rest.ApprovalResource
import com.openbank.libs.approval.ApprovalStore
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Regression for #3029: `PATCH /api/v1/consents/approvals/{id}` answered **500** to a JSON `null`
 * body.
 *
 * The parameter was declared non-nullable (`request: DecideApprovalRequest`), but Kotlin's
 * null-safety is a compile-time property — JAX-RS/Jackson hands a `null` straight through at
 * runtime, so the first field access threw NPE and the generic mapper turned it into 500 on a
 * **four-eyes approval endpoint**. The property being violated is the one the authenticated fuzz
 * lane asserts: no input, however malformed, produces a 5xx (ADR-0080).
 *
 * Found by the first ever working run of `api-fuzz-authenticated.yml` — the lane had never started
 * before, so nothing had ever sent this request. The identical shape existed in all sixteen
 * `ApprovalResource` copies; this test covers the one with a reproduced failure.
 *
 * `requireNotNull` runs before `checkerId()`, so no `SecurityIdentity` is needed here — a null body
 * is rejected before any identity is resolved, which is also the behaviour you want: an
 * unparseable request should not reach authorization-adjacent code at all.
 *
 * Note on "fail first": this test could not have compiled against the old signature, since it
 * passes `null` where the parameter was non-nullable. What it locks in is the new contract — a
 * missing body is a client error, not a server error — so a future change back to a non-nullable
 * parameter breaks the build rather than silently restoring the 500.
 */
class ApprovalNullBodyTest {

    @Test
    fun `a null request body is a client error, never a 500`(): Unit = runBlocking {
        val resource = ApprovalResource(mockk<ApprovalStore>(relaxed = true))

        assertThatThrownBy { runBlocking { resource.decide("appr-1", null) } }
            // libs-runtime's CommonExceptionMappers maps IllegalArgumentException to 400 — the same
            // guard ConsentResource already uses for its own request bodies.
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("request body is required")
    }
}
