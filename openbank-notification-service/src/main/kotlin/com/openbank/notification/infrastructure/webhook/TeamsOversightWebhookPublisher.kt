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
import org.jboss.logging.Logger
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
            return Uni.createFrom().item(false)
        }
        val body = OversightWebhook.renderSlackPayload(signal) // {"text":"..."} is valid for Teams too
        val req = EgressRequest(
            method = "POST",
            url = target,
            headers = mapOf("Content-Type" to "application/json"),
            body = body.toByteArray(Charsets.UTF_8),
        )

        return NotificationEgress.send(http, req)
            .map { resp ->
                val ok = resp.status in HTTP_OK_RANGE
                log.infof(
                    "notification.webhook.sent provider=teams template=%s status=%s http=%d url=%s ok=%b",
                    signal.template.name,
                    signal.status.name,
                    resp.status,
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
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(3)
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(3)
        private const val MAX_RESPONSE_BYTES = 64 * 1024
        private const val AWAIT_TIMEOUT_SECONDS = 4
    }
}
