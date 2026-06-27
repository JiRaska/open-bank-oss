// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.temporal

import io.temporal.activity.ActivityExecutionContext
import io.temporal.activity.ActivityInfo
import io.temporal.common.interceptors.ActivityInboundCallsInterceptor
import io.temporal.common.interceptors.ActivityInboundCallsInterceptor.ActivityInput
import io.temporal.common.interceptors.ActivityInboundCallsInterceptor.ActivityOutput
import io.temporal.common.interceptors.WorkerInterceptor
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor
import io.temporal.failure.ApplicationFailure
import org.jboss.logging.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private const val OPA_TIMEOUT_MS = 200L
private const val HTTP_OK = 200

/**
 * Temporal [WorkerInterceptor] that gates every activity execution against an OPA
 * sidecar policy (`openbank/temporal/allow`).
 *
 * Before each `execute`, a synchronous HTTP POST is sent to:
 * ```
 * POST http://localhost:8181/v1/data/openbank/temporal/allow
 * { "input": { "activity": "<type>", "namespace": "<ns>", "taskQueue": "<queue>" } }
 * ```
 * with a 200 ms timeout. If OPA returns `{"result": false}`, or the request times out
 * / fails, the activity is rejected with an [ApplicationFailure] reason `OPA_DENIED`.
 *
 * Uses `java.net.http.HttpClient` (JDK 11+) — no additional HTTP dependencies.
 */
class OpaActivityInterceptor(private val opaUrl: String = "http://localhost:8181/v1/data/openbank/temporal/allow") :
    WorkerInterceptor {

    private val log = Logger.getLogger(OpaActivityInterceptor::class.java)

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(OPA_TIMEOUT_MS))
        .build()

    override fun interceptWorkflow(next: WorkflowInboundCallsInterceptor): WorkflowInboundCallsInterceptor = next

    override fun interceptActivity(next: ActivityInboundCallsInterceptor): ActivityInboundCallsInterceptor =
        OpaActivityInboundCallsInterceptor(next, http, opaUrl, log)
}

private class OpaActivityInboundCallsInterceptor(
    private val next: ActivityInboundCallsInterceptor,
    private val http: HttpClient,
    private val opaUrl: String,
    private val log: Logger,
) : ActivityInboundCallsInterceptor {

    private lateinit var ctx: ActivityExecutionContext

    override fun init(context: ActivityExecutionContext) {
        ctx = context
        next.init(context)
    }

    override fun execute(input: ActivityInput): ActivityOutput {
        val info: ActivityInfo = ctx.info
        val activityType = info.activityType
        val namespace = info.activityNamespace
        val taskQueue = info.activityTaskQueue

        checkOpa(activityType, namespace, taskQueue)
        return next.execute(input)
    }

    private fun checkOpa(activityType: String, namespace: String, taskQueue: String) {
        val body = """{"input":{"activity":"$activityType","namespace":"$namespace","taskQueue":"$taskQueue"}}"""

        val allowed = runCatching {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(opaUrl))
                .timeout(Duration.ofMillis(OPA_TIMEOUT_MS))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()

            val response = http.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() != HTTP_OK) {
                log.warnf(
                    "OPA returned HTTP %d for activity=%s — denying",
                    response.statusCode(),
                    activityType,
                )
                return@runCatching false
            }

            // OPA v1 data API: {"result": true} or {"result": false}
            // Simple string match avoids pulling in a JSON library.
            val responseBody = response.body()
            !responseBody.contains("\"result\":false") && responseBody.contains("\"result\":true")
        }.getOrElse { ex ->
            log.warnf(
                "OPA call timed out or failed for activity=%s (%s) — denying",
                activityType,
                ex.message,
            )
            false
        }

        if (!allowed) {
            throw ApplicationFailure.newFailure(
                "OPA denied activity '$activityType' in namespace '$namespace' on queue '$taskQueue'",
                "OPA_DENIED",
            )
        }
    }
}
