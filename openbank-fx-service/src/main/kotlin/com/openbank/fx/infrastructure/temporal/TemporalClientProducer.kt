// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.fx.infrastructure.temporal

import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import org.eclipse.microprofile.config.inject.ConfigProperty

@ApplicationScoped
class TemporalClientProducer(
    @ConfigProperty(name = "openbank.temporal.server-url", defaultValue = "localhost:7233")
    private val serverUrl: String,
    @ConfigProperty(name = "openbank.temporal.namespace", defaultValue = "openbank-fx")
    private val namespace: String,
) {

    // Lazy so no TCP connection attempt happens when temporal.enabled=false.
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
