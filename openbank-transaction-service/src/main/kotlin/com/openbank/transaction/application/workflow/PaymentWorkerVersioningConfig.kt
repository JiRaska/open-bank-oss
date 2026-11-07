// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.application.workflow

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import io.temporal.common.WorkerDeploymentVersion
import io.temporal.worker.WorkerDeploymentOptions
import io.temporal.worker.WorkerOptions
import java.util.Optional

@ConfigMapping(prefix = "openbank.transaction.worker.versioning")
interface PaymentWorkerVersioningConfig {
    @WithDefault("false")
    fun enabled(): Boolean
    fun deploymentName(): Optional<String>
    fun buildId(): Optional<String>
}

/** Version identity is mandatory before polling; mutable release labels cannot identify histories. */
internal fun paymentWorkerOptions(config: PaymentWorkerVersioningConfig): WorkerOptions {
    require(config.enabled()) {
        "SDK 1.40 payment workers require versioning; API-only replicas must disable the payment worker"
    }
    val deployment = config.deploymentName().orElse("")
    val build = config.buildId().orElse("")
    require(deployment.matches(Regex("[a-z][a-z0-9-]{0,62}"))) {
        "Versioned payment worker requires a stable deployment-name"
    }
    require(build.matches(Regex("[a-f0-9]{40}([a-f0-9]{24})?"))) {
        "Versioned payment worker requires an immutable full Git SHA or image digest as build-id"
    }
    return WorkerOptions.newBuilder()
        .setDeploymentOptions(
            WorkerDeploymentOptions.newBuilder()
                .setUseVersioning(true)
                .setVersion(WorkerDeploymentVersion(deployment, build))
                .build(),
        )
        .build()
}
