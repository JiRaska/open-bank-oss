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
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Slack incoming-webhook adapter for the oversight side-channel (ADR-0059).
 *
 * Off by default. A no-op unless `openbank.notification.webhook.slack.enabled=true`
 * AND a URL is configured (injected from Vault via ExternalSecret, never in git).
 * Uses the JDK HttpClient (no new dependency); the call is async and best-effort —
 * a failure is logged and swallowed, never propagated into notification dispatch.
 *
 * Only OversightWebhook.renderSlackPayload (the allow-listed, PII-free schema) is
 * ever sent — the publisher has no access to the notification's variables/recipient.
 */
@ApplicationScoped
@Unremovable
class SlackOversightWebhookPublisher : OversightWebhookPublisher {

    // Plain Boolean with a defaultValue — NOT Optional<Boolean>; combining
    // Optional with defaultValue throws a ConfigRecorder DeploymentException.
    @ConfigProperty(name = "openbank.notification.webhook.slack.enabled", defaultValue = "false")
    var enabled: Boolean = false

    // Optional<String>, NOT a plain String with defaultValue="": the yaml binds
    // ${SLACK_WEBHOOK_URL:} which expands to an EMPTY value when unset, and
    // SmallRye rejects an empty value for a non-optional String (ConfigRecorder
    // DeploymentException). Optional maps the empty/absent value to Optional.empty.
    @ConfigProperty(name = "openbank.notification.webhook.slack.url")
    var url: java.util.Optional<String> = java.util.Optional.empty()

    private val log = Logger.getLogger(SlackOversightWebhookPublisher::class.java)

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    }

    override fun publish(signal: OversightSignal): Uni<Boolean> {
        val target = url.orElse("")
        if (!enabled || target.isBlank()) {
            // Disabled or unconfigured → no-op, nothing egresses (ADR-0059 D4).
            return Uni.createFrom().item(false)
        }
        val body = OversightWebhook.renderSlackPayload(signal)
        val req = HttpRequest.newBuilder()
            .uri(URI.create(target))
            .timeout(Duration.ofSeconds(3))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        return Uni.createFrom().completionStage(http.sendAsync(req, HttpResponse.BodyHandlers.ofString()))
            .map { resp ->
                val ok = resp.statusCode() in 200..299
                // Audit (ADR-0059 D5): template/status + masked URL only — never content.
                log.infof(
                    "notification.webhook.sent provider=slack template=%s status=%s http=%d url=%s ok=%b",
                    signal.template.name,
                    signal.status.name,
                    resp.statusCode(),
                    OversightWebhook.maskUrl(target),
                    ok,
                )
                ok
            }
            .ifNoItem().after(Duration.ofSeconds(4)).recoverWithItem(false)
            .onFailure().recoverWithItem { e ->
                log.warnf(
                    "notification.webhook.sent provider=slack template=%s FAILED: %s",
                    signal.template.name,
                    e.message,
                )
                false
            }
    }
}
