// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.webhook

import com.openbank.notification.application.OversightSignal
import com.openbank.notification.application.OversightWebhook
import com.openbank.notification.application.port.out.OversightWebhookPublisher
import io.quarkus.arc.Unremovable
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Microsoft Teams incoming-webhook adapter for the oversight side-channel (ADR-0059).
 *
 * Teams incoming webhooks accept `{"text": "..."}` for simple connectors — the same
 * schema as Slack for plain-text messages. The PII-free payload is identical:
 * `OversightWebhook.renderSlackPayload` produces `{"text":"..."}` which Teams accepts.
 *
 * Off by default (`openbank.notification.webhook.teams.enabled=false`).
 * URL is injected from Vault via ExternalSecret, never in git.
 *
 * CDI injection: `NotificationConsumer` uses `@All Instance&lt;OversightWebhookPublisher&gt;`
 * to iterate all active adapters. Direct inject of `OversightWebhookPublisher` is NOT
 * used — that would be ambiguous with two implementations on the classpath.
 */
@ApplicationScoped
@Unremovable
class TeamsOversightWebhookPublisher : OversightWebhookPublisher {

    @org.eclipse.microprofile.config.inject.ConfigProperty(
        name = "openbank.notification.webhook.teams.enabled",
        defaultValue = "false",
    )
    var enabled: Boolean = false

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "openbank.notification.webhook.teams.url")
    var url: java.util.Optional<String> = java.util.Optional.empty()

    private val log = Logger.getLogger(TeamsOversightWebhookPublisher::class.java)

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS.toLong())).build()
    }

    override fun publish(signal: OversightSignal): Uni<Boolean> {
        val target = url.orElse("")
        if (!enabled || target.isBlank()) {
            return Uni.createFrom().item(false)
        }
        val body = OversightWebhook.renderSlackPayload(signal) // {"text":"..."} is valid for Teams too
        val req = HttpRequest.newBuilder()
            .uri(URI.create(target))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS.toLong()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        return Uni.createFrom().completionStage(http.sendAsync(req, HttpResponse.BodyHandlers.ofString()))
            .map { resp ->
                val ok = resp.statusCode() in HTTP_OK_RANGE
                log.infof(
                    "notification.webhook.sent provider=teams template=%s status=%s http=%d url=%s ok=%b",
                    signal.template.name,
                    signal.status.name,
                    resp.statusCode(),
                    OversightWebhook.maskUrl(target),
                    ok,
                )
                ok
            }
            .ifNoItem().after(Duration.ofSeconds(AWAIT_TIMEOUT_SECONDS.toLong())).recoverWithItem(false)
            .onFailure().recoverWithItem { e ->
                log.warnf(
                    "notification.webhook.sent provider=teams template=%s FAILED: %s",
                    signal.template.name,
                    e.message,
                )
                false
            }
    }

    companion object {
        private val HTTP_OK_RANGE = 200..299
        private const val CONNECT_TIMEOUT_SECONDS = 3
        private const val REQUEST_TIMEOUT_SECONDS = 3
        private const val AWAIT_TIMEOUT_SECONDS = 4
    }
}
