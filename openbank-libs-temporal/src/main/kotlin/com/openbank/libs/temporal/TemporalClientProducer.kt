// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.temporal

import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.uber.m3.tally.RootScopeBuilder
import com.uber.m3.util.Duration
import io.micrometer.core.instrument.MeterRegistry
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.common.converter.DataConverter
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.common.reporter.MicrometerClientStatsReporter
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces

/**
 * The single fleet-wide `WorkflowClient` producer (ADR-0209 D1, issue #2572).
 *
 * Replaces 14 copies that had drifted into three behavioural shapes. Licensed Apache-2.0 and living
 * in the Apache-2.0 `openbank-libs-runtime` on purpose: 8 of the 14 consumers are AGPL-3.0-only
 * modules, and `rules.yaml: agpl_modules.rule` forbids an Apache-2.0 module depending on an AGPL
 * one — never the reverse. Extracting into an AGPL library would have put the 6 Apache consumers in
 * violation.
 *
 * The client is `by lazy` so that no TCP connection to the Temporal frontend is attempted at bean
 * creation: a service with `openbank.temporal.enabled=false` (and every `@QuarkusTest` that swaps in
 * an `@Alternative @Priority(1)` in-process `TestWorkflowEnvironment` producer) never dials out.
 * Note that `@ApplicationScoped` is lazy anyway, so this is belt-and-braces rather than the gate.
 */
@ApplicationScoped
class TemporalClientProducer(private val config: TemporalConfig, private val meterRegistry: MeterRegistry) {

    private val client: WorkflowClient by lazy {
        val stubsOptions = WorkflowServiceStubsOptions.newBuilder().setTarget(config.serverUrl())
        if (config.metricsEnabled()) {
            stubsOptions.setMetricsScope(
                RootScopeBuilder()
                    .reporter(MicrometerClientStatsReporter(meterRegistry))
                    .reportEvery(Duration.ofSeconds(METRICS_REPORT_INTERVAL_SECONDS)),
            )
        }
        WorkflowClient.newInstance(
            WorkflowServiceStubs.newServiceStubs(stubsOptions.build()),
            WorkflowClientOptions.newBuilder()
                .setNamespace(config.namespace())
                .setDataConverter(kotlinAwareDataConverter())
                .build(),
        )
    }

    /**
     * Temporal's default JSON payload converter builds a plain Jackson ObjectMapper, which cannot
     * construct a Kotlin data class — those have no no-arg constructor. Any workflow argument or
     * return value that is a Kotlin data class fails with
     *
     *     DataConverterException: Cannot construct instance of `X` (no Creators, like default
     *     constructor, exist)
     *
     * and the workflow task fails in a loop: the activity has already run, so the work is done and
     * the journey still never advances. Measured on campaign-service, whose journey passes
     * `List<CampaignStep>` (#2749); services that only pass Strings and UUIDs never hit it, which is
     * why this survived in a fleet with several Temporal consumers.
     *
     * Additive: this only teaches the existing JSON converter to build Kotlin types. Payload
     * encoding is unchanged, so in-flight workflows and histories written before this stay readable.
     */
    internal fun kotlinAwareDataConverter(): DataConverter = DefaultDataConverter
        .newDefaultInstance()
        .withPayloadConverterOverrides(
            JacksonJsonPayloadConverter(
                JacksonJsonPayloadConverter.newDefaultObjectMapper().registerKotlinModule(),
            ),
        )

    @Produces
    @ApplicationScoped
    fun workflowClient(): WorkflowClient = client

    private companion object {
        /** Matches the reporting interval every extracted copy used. */
        const val METRICS_REPORT_INTERVAL_SECONDS = 1.0
    }
}
