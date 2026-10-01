// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.webhook

import com.openbank.libs.security.EgressRequest
import com.openbank.libs.security.EgressResolver
import com.openbank.libs.security.SafeHttpClient
import com.openbank.notification.application.OversightSignal
import com.openbank.notification.application.OversightWebhook
import com.openbank.notification.application.port.out.OversightWebhookPublisher
import com.openbank.notification.infrastructure.egress.NotificationEgress
import io.quarkus.arc.Unremovable
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Duration

/**
 * Slack incoming-webhook adapter for the oversight side-channel (ADR-0059).
 *
 * Off by default. A no-op unless `openbank.notification.webhook.slack.enabled=true`
 * AND a URL is configured (injected from Vault via ExternalSecret, never in git).
 * Egress goes through SafeHttpClient (ADR-0320 P1 allow-list); the call is async and best-effort —
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

    // ADR-0320 P1: the webhook URL is configurable, so the host must be on the egress allow-list.
    @org.eclipse.microprofile.config.inject.ConfigProperty(
        name = NotificationEgress.ALLOWED_HOSTS_PROPERTY,
        defaultValue = NotificationEgress.DEFAULT_ALLOWED_HOSTS,
    )
    lateinit var allowedHosts: List<String>

    /** Visible for testing: lets a unit test pin a stub host to loopback. */
    internal var resolver: EgressResolver = EgressResolver.SYSTEM

    private val http: SafeHttpClient by lazy {
        NotificationEgress.client(allowedHosts, resolver, CONNECT_TIMEOUT, REQUEST_TIMEOUT, MAX_RESPONSE_BYTES)
    }

    override fun publish(signal: OversightSignal): Uni<Boolean> {
        val target = url.orElse("")
        if (!enabled || target.isBlank()) {
            // Disabled or unconfigured → no-op, nothing egresses (ADR-0059 D4).
            return Uni.createFrom().item(false)
        }
        val body = OversightWebhook.renderSlackPayload(signal)
        val req = EgressRequest(
            method = "POST",
            url = target,
            headers = mapOf("Content-Type" to "application/json"),
            body = body.toByteArray(Charsets.UTF_8),
        )

        return NotificationEgress.send(http, req)
            .map { resp ->
                val ok = resp.status in HTTP_OK_RANGE
                // Audit (ADR-0059 D5): template/status + masked URL only — never content.
                log.infof(
                    "notification.webhook.sent provider=slack template=%s status=%s http=%d url=%s ok=%b",
                    signal.template.name,
                    signal.status.name,
                    resp.status,
                    OversightWebhook.maskUrl(target),
                    ok,
                )
                ok
            }
            .ifNoItem().after(AWAIT_TIMEOUT).recoverWithItem(false)
            .onFailure().recoverWithItem { e ->
                log.warnf(
                    "notification.webhook.sent provider=slack template=%s FAILED: %s",
                    signal.template.name,
                    e.message,
                )
                false
            }
    }

    companion object {
        private val HTTP_OK_RANGE = 200..299
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(3)
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(3)
        private val AWAIT_TIMEOUT: Duration = Duration.ofSeconds(4)
        private const val MAX_RESPONSE_BYTES = 64 * 1024
    }
}
