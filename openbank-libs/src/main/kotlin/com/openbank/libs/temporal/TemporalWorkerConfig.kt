// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.temporal

import io.quarkus.runtime.StartupEvent
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import io.temporal.worker.WorkerFactory
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.util.Optional

/**
 * CDI bean that bootstraps a Temporal Worker on startup.
 *
 * Enabled only when `openbank.temporal.enabled=true` (default: false) so the
 * library can be on the classpath of every service without forcing each one to
 * stand up a Temporal connection.
 *
 * Config properties:
 * - `openbank.temporal.server-url`   — Temporal frontend address (default: `localhost:7233`)
 * - `openbank.temporal.namespace`    — Temporal namespace         (default: `openbank-default`)
 * - `openbank.temporal.task-queue`   — task-queue name; Optional to avoid SRCFG00040 when unset
 * - `openbank.temporal.enabled`      — set to `true` to start the worker (default: `false`)
 */
@ApplicationScoped
class TemporalWorkerConfig {
    private val log = Logger.getLogger(TemporalWorkerConfig::class.java)

    @ConfigProperty(name = "openbank.temporal.server-url", defaultValue = "localhost:7233")
    lateinit var serverUrl: String

    @ConfigProperty(name = "openbank.temporal.namespace", defaultValue = "openbank-default")
    lateinit var namespace: String

    /** Optional — services that do not configure a task-queue leave this absent. */
    @ConfigProperty(name = "openbank.temporal.task-queue")
    lateinit var taskQueue: Optional<String>

    @ConfigProperty(name = "openbank.temporal.enabled", defaultValue = "false")
    var enabled: Boolean = false

    @Suppress("UnusedParameter")
    fun onStart(@Observes event: StartupEvent) {
        if (!enabled) {
            log.debug("Temporal worker disabled (openbank.temporal.enabled=false) — skipping startup")
            return
        }

        val queue = taskQueue.orElseThrow {
            IllegalStateException(
                "openbank.temporal.task-queue must be set when openbank.temporal.enabled=true",
            )
        }

        log.infof(
            "Starting Temporal worker: server=%s namespace=%s taskQueue=%s",
            serverUrl,
            namespace,
            queue,
        )

        val stubs = WorkflowServiceStubs.newInstance(
            WorkflowServiceStubsOptions.newBuilder()
                .setTarget(serverUrl)
                .build(),
        )

        val client = WorkflowClient.newInstance(
            stubs,
            WorkflowClientOptions.newBuilder()
                .setNamespace(namespace)
                .build(),
        )

        val factory = WorkerFactory.newInstance(client)
        factory.newWorker(queue)
        factory.start()

        log.infof("Temporal worker started on task-queue '%s'", queue)
    }
}
