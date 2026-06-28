// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finops.infrastructure.temporal

import com.uber.m3.tally.RootScopeBuilder
import com.uber.m3.util.Duration
import io.micrometer.core.instrument.MeterRegistry
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.common.reporter.MicrometerClientStatsReporter
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces

@ApplicationScoped
class TemporalClientProducer(private val config: TemporalConfig, private val meterRegistry: MeterRegistry) {
    private val client: WorkflowClient by lazy {
        val scope = RootScopeBuilder()
            .reporter(MicrometerClientStatsReporter(meterRegistry))
            .reportEvery(Duration.ofSeconds(1.0))
        val stubs = WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder()
                .setTarget(config.serverUrl())
                .setMetricsScope(scope)
                .build(),
        )
        WorkflowClient.newInstance(
            stubs,
            WorkflowClientOptions.newBuilder().setNamespace(config.namespace()).build(),
        )
    }

    @Produces
    @ApplicationScoped
    fun workflowClient(): WorkflowClient = client
}
