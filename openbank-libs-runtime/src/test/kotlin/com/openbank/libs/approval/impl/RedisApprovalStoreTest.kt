// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.approval.impl

import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.ApprovalStoreContractTest
import io.mockk.every
import io.mockk.mockk
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.redis.datasource.value.ReactiveValueCommands
import io.quarkus.redis.datasource.value.SetArgs
import io.smallrye.mutiny.Uni
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The production [ApprovalStore] bound to the shared contract (issue #3349).
 *
 * `RedisApprovalStore.decide` refusing `decidedBy == approval.makerId` is the single fleet-wide
 * enforcement point for segregation of duties on a four-eyes action: 18 services inherit it, and a
 * dozen threat models cite that one line verbatim as their STRIDE elevation-of-privilege mitigation.
 * Before this class, **deleting it left every suite green** — `AuthorizeInterceptorTest`'s double
 * re-implemented the same check (and no test invoked even that copy), `CommonExceptionMappersTest`
 * constructs the exception by hand, and notification-service's `ApprovalStoreWiringIT` walks only the
 * happy path with a distinct checker. Measured: removing the guard now fails 3 of the 4 contract
 * cases, and the control case still passes — which is what makes the redness attributable.
 *
 * The Redis commands are backed by an in-memory map rather than stubbed per call, so
 * `create` -> `encode` -> `decode` -> `decide` really round-trips: that is what proves `makerId`
 * survives the pipe-delimited value into the comparison. A per-call `every { get(...) } returns
 * <hand-written payload>` would assume that rather than test it. A container would add a Docker
 * dependency — and `RedisTestResource` aborts (green) when Docker is absent, which is the wrong
 * failure mode for the only test of a security invariant.
 *
 * **If the guard ever moves** — into `AuthorizeInterceptor`, a REST filter, or a domain service —
 * move this binding with it. Deleting it because "the line is not here any more" restores exactly
 * the state #3349 documents.
 *
 * Out of scope by construction: whether `makerId` and the checker's id are formatted identically for
 * the same human in production. `ApprovalResource.checkerId()` uses `.principal.name` and warns in a
 * comment that `.subject` would silently break the comparison; this test hands both sides a literal,
 * so it proves the guard fires when the strings match, not that they ever match. An HTTP-driven IT
 * is what would close that half.
 */
class RedisApprovalStoreTest : ApprovalStoreContractTest() {

    private val clock = Clock.fixed(Instant.parse("2026-08-02T21:00:00Z"), ZoneOffset.UTC)

    override fun newStore(): ApprovalStore {
        val backing = mutableMapOf<String, String>()
        val values = mockk<ReactiveValueCommands<String, String>>()
        every { values.get(any()) } answers { Uni.createFrom().item(backing[firstArg<String>()]) }
        every { values.set(any<String>(), any<String>(), any<SetArgs>()) } answers {
            backing[firstArg()] = secondArg()
            Uni.createFrom().voidItem()
        }
        val redis = mockk<ReactiveRedisDataSource>()
        every { redis.value(String::class.java) } returns values
        return RedisApprovalStore(redis, clock)
    }
}
