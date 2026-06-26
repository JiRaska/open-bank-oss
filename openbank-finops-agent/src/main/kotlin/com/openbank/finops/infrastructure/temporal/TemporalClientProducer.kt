// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.finops.infrastructure.temporal

import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces

@ApplicationScoped
class TemporalClientProducer(private val config: TemporalConfig) {
    private val serverUrl get() = config.serverUrl()
    private val namespace get() = config.namespace()

    private val client: WorkflowClient by lazy {
        val stubs = WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder().setTarget(serverUrl).build(),
        )
        WorkflowClient.newInstance(
            stubs,
            WorkflowClientOptions.newBuilder().setNamespace(namespace).build(),
        )
    }

    @Produces
    @ApplicationScoped
    fun workflowClient(): WorkflowClient = client
}
