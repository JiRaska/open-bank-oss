// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.temporal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * The one behaviour of [TemporalClientProducer] that can be asserted without a Temporal frontend,
 * and the one that breaks loudly if someone "simplifies" the `by lazy` away: constructing the bean
 * must not read a single config property, because reading them is what precedes dialling
 * `openbank.temporal.server-url` over gRPC.
 *
 * All 14 extracted copies relied on this. A service with `openbank.temporal.enabled=false`, and
 * every `@QuarkusTest` that swaps in an `@Alternative @Priority(1)` in-process
 * `TestWorkflowEnvironment` producer, must never cause an outbound connection attempt.
 *
 * Falsified before being committed: dropping `by lazy` from `TemporalClientProducer.client` (making
 * it an eagerly-initialised `val`) turns this red on the `serverUrl()` verification — and, as it
 * happens, also hangs the JVM building real service stubs, which is precisely the failure mode.
 */
class TemporalClientProducerLazinessTest {

    @Test
    fun `constructing the producer reads no config and so opens no connection`() {
        val config = mockk<TemporalConfig>(relaxed = true)

        TemporalClientProducer(config, SimpleMeterRegistry())

        verify(exactly = 0) { config.serverUrl() }
        verify(exactly = 0) { config.namespace() }
        verify(exactly = 0) { config.metricsEnabled() }
    }
}
