// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.application.workflow

import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.temporal.client.WorkflowClientOptions
import io.temporal.common.converter.DataConverter
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.testing.TestEnvironmentOptions
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Shared by [SepaPaymentWorkflowHistoryRecorder] and [SepaPaymentWorkflowReplayTest] (#15724).
 *
 * The data converter is a copy of `TemporalClientProducer.kotlinAwareDataConverter` (internal to
 * openbank-libs-temporal, so it cannot be called from here). Every payload this workflow carries is
 * a UUID or an enum, which both converters encode identically.
 */
internal object SepaPaymentWorkflowHistoryFixtures {

    /** Module-relative; Gradle runs tests with the module directory as the working directory. */
    val HISTORY_DIR: Path = Paths.get("src", "test", "resources", "temporal-histories")

    /** Fixed worker identity, so no recorded history carries the recording machine's host name. */
    const val IDENTITY = "sepa-history-recorder"

    /**
     * Every path through `SepaPaymentWorkflowImpl.process` as of the recording, by history file name
     * (without `.json`). The replay test fails if any of these files is missing, so a history cannot
     * be dropped silently.
     */
    val EXPECTED_HISTORIES: List<String> = listOf(
        "clear-scheme-accepted-settled-completed",
        "clear-scheme-accepted-settlement-unavailable-processing",
        "clear-scheme-accepted-settlement-not-settled-processing",
        "clear-scheme-rejected-ac04-account-closed",
        "clear-scheme-rejected-ac06-account-frozen",
        "clear-scheme-rejected-rc01-invalid-bic",
        "clear-scheme-rejected-rr04-aml-hold",
        "clear-scheme-rejected-unmapped-technical-error",
        "clear-scheme-gateway-unavailable-validated",
        "clear-scheme-submission-disabled-validated",
        "block-sanctions-hit-rejected",
        "review-potential-hit-received",
        "review-screening-unavailable-received",
        "screen-activity-retried-then-clear-completed",
        "submit-activity-retried-then-completed",
        "screen-activity-retries-exhausted-workflow-failed",
        "cancelled-during-screening",
    )

    fun dataConverter(): DataConverter = DefaultDataConverter
        .newDefaultInstance()
        .withPayloadConverterOverrides(
            JacksonJsonPayloadConverter(
                JacksonJsonPayloadConverter.newDefaultObjectMapper().registerKotlinModule(),
            ),
        )

    fun environmentOptions(): TestEnvironmentOptions = TestEnvironmentOptions.newBuilder()
        .setWorkflowClientOptions(
            WorkflowClientOptions.newBuilder()
                .setDataConverter(dataConverter())
                .setIdentity(IDENTITY)
                .build(),
        )
        .build()
}
