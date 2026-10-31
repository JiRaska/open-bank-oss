// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.egress

import com.openbank.libs.security.EgressPolicy
import com.openbank.libs.security.EgressRequest
import com.openbank.libs.security.EgressResolver
import com.openbank.libs.security.EgressResponse
import com.openbank.libs.security.SafeHttpClient
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.infrastructure.Infrastructure
import java.time.Duration

/**
 * Third-party egress for notification-service (ADR-0320 P1): Slack/Teams oversight webhooks and
 * FCM. Every such URL is configurable — a webhook URL comes from Vault, FCM's token endpoint from
 * the service-account JSON — so each call goes through [SafeHttpClient]: only hosts listed in
 * `openbank.notification.egress.allowed-hosts` are reachable, DNS is pinned, redirects are not
 * followed and the response body is size-capped.
 *
 * APNs is deliberately NOT here: Apple's provider API is HTTP/2-only and [SafeHttpClient] speaks
 * HTTP/1.1, so `ApnsPushSender` keeps its JDK client against a fixed, non-configurable Apple host.
 */
object NotificationEgress {
    /** Teams webhooks are per-tenant hosts (`<tenant>.webhook.office.com`) and must be added per env. */
    const val ALLOWED_HOSTS_PROPERTY = "openbank.notification.egress.allowed-hosts"
    const val DEFAULT_ALLOWED_HOSTS = "hooks.slack.com,oauth2.googleapis.com,fcm.googleapis.com"

    fun client(
        allowedHosts: List<String>,
        resolver: EgressResolver,
        connectTimeout: Duration,
        callTimeout: Duration,
        maxResponseBytes: Int,
    ): SafeHttpClient = SafeHttpClient(
        EgressPolicy.fromConfig(allowedHosts),
        resolver = resolver,
        connectTimeout = connectTimeout,
        readTimeout = callTimeout,
        maxResponseBytes = maxResponseBytes,
        callTimeout = callTimeout,
    )

    /** Construct the client and send on a worker so configuration errors also become Uni failures. */
    fun send(client: () -> SafeHttpClient, request: EgressRequest): Uni<EgressResponse> =
        Uni.createFrom().item { client().send(request) }
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
}
