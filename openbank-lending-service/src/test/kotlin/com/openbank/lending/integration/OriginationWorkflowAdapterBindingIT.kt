// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.integration

import com.openbank.lending.application.port.out.OriginationWorkflowPort
import com.openbank.lending.infrastructure.adapter.NoOpOriginationWorkflowPort
import com.openbank.lending.infrastructure.temporal.TemporalOriginationWorkflowAdapter
import com.openbank.lending.it.PostgresRedisTestResource
import io.quarkus.arc.Arc
import io.quarkus.arc.ClientProxy
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Boots the application with `openbank.temporal.enabled=true` — the value the packaged image now
 * carries (`%prod` in `application.yaml`) — and proves augmentation really delivers
 * [TemporalOriginationWorkflowAdapter] (#6085).
 *
 * ## Why booting matters more than asserting
 *
 * This is the first thing in this repo to start the application with the Temporal origination
 * adapter actually bound. #6057's sibling test found, by doing exactly this, that flipping the
 * property alone would have replaced a silent no-op with a pod that does not start: SmallRye
 * validates every property under a registered `@ConfigMapping` prefix against that root's members,
 * and the root is registered exactly when its consumer is bound, so the validation had never once
 * run (`SRCFG00050`). Here the `openbank.temporal` root is `com.openbank.libs.temporal
 * .TemporalConfig`, which declares `enabled`, `serverUrl`, `namespace`, `taskQueue` and
 * `metricsEnabled` — every key this service sets — but that is an argument, and a boot is
 * evidence. The distinction is the entire reason this test exists rather than a comment.
 *
 * The worker is left disabled (`%test` already does this): the timers worker needs a real Temporal
 * frontend and would fail boot on connection refused. The client half under test does not connect
 * eagerly, so the binding is observable without one.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(TemporalEnabledProfile::class)
class OriginationWorkflowAdapterBindingIT {

    @Inject
    lateinit var workflowPort: OriginationWorkflowPort

    @Test
    fun `the real Temporal origination adapter is present in the application`() {
        assertThat(Arc.container().instance(TemporalOriginationWorkflowAdapter::class.java).isAvailable)
            .describedAs(
                "openbank.temporal.enabled=true must make TemporalOriginationWorkflowAdapter a " +
                    "bean. Absent, the @Default NoOpOriginationWorkflowPort is what CDI binds — " +
                    "#6085, no origination durable timer ever armed while the no-op returned the " +
                    "real adapter's success value.",
            )
            .isTrue()
    }

    @Test
    fun `the injected workflow port resolves to the Temporal adapter, not the no-op`() {
        // Read from the effect — the class behind the client proxy — rather than from a second copy
        // of the configuration, which would agree with the first and prove nothing.
        assertThat(ClientProxy.unwrap(workflowPort)).isInstanceOf(TemporalOriginationWorkflowAdapter::class.java)
    }
}

/**
 * The other half of the same claim: with the offline value, the real adapter is genuinely absent
 * and the no-op is what binds. Without this, the test above could pass for a reason unrelated to
 * the gate — an adapter that was never gated at all would satisfy it just as well.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(TemporalDisabledProfile::class)
class InertOriginationWorkflowAdapterBindingIT {

    @Inject
    lateinit var workflowPort: OriginationWorkflowPort

    @Test
    fun `the Temporal adapter is absent and the no-op binds when Temporal is not enabled`() {
        assertThat(Arc.container().instance(TemporalOriginationWorkflowAdapter::class.java).isAvailable).isFalse()
        assertThat(ClientProxy.unwrap(workflowPort)).isInstanceOf(NoOpOriginationWorkflowPort::class.java)
    }
}

/**
 * The workflow-port selection the packaged image carries.
 *
 * Values are literals on purpose: a `QuarkusTestProfile` loads in a different classloader from the
 * test class, so anything derived in `getConfigOverrides()` is computed twice and the two copies
 * need not agree.
 */
class TemporalEnabledProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "openbank.temporal.enabled" to "true",
        "lending.origination.worker.enabled" to "false",
    )
}

/** The offline selection ADR-0028 D3 protects: no real Temporal frontend needed to boot. */
class TemporalDisabledProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "openbank.temporal.enabled" to "false",
        "lending.origination.worker.enabled" to "false",
    )
}
