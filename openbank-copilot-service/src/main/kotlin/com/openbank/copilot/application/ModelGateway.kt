// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.copilot.application

import com.openbank.copilot.application.port.out.ModelProvider
import com.openbank.copilot.domain.model.ModelDescriptor
import com.openbank.copilot.domain.model.ModelRequest
import com.openbank.copilot.domain.model.ModelResponse
import com.openbank.copilot.domain.model.Sensitivity
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.security.MessageDigest

/**
 * The single seam every model call passes through (ADR-0089 D6; the gateway is a trust boundary
 * in the path of every completion). Responsibilities:
 *
 *  - resolve a request's `model` id to a registered [ModelDescriptor] (from [ModelGatewayConfig]);
 *  - dispatch to the [ModelProvider] whose [ModelProvider.key] matches the descriptor;
 *  - emit an AI-attributed [AuditEvent] for every completion (`model_id`, `model_version`,
 *    `prompt_hash`, token usage, stop reason) — ADR-0031 D5; the completion is attributed to the
 *    customer the assistant is acting for ([actorId]), never to the gateway;
 *  - pin sensitive context to a self-hosted model (ADR-0089 D6 routing hook).
 *
 * Vendor-neutral: swapping or adding a model never touches this class. If no model is configured
 * it self-seeds a `mock` descriptor so the sandbox boots without extra YAML.
 */
@ApplicationScoped
class ModelGateway {

    @Inject
    lateinit var config: ModelGatewayConfig

    @Inject
    lateinit var providers: Instance<ModelProvider>

    @Inject
    lateinit var auditPublisher: AuditEventPublisher

    private val log = Logger.getLogger(ModelGateway::class.java)

    private lateinit var registry: Map<String, ModelDescriptor>
    private lateinit var providersByKey: Map<String, ModelProvider>

    @PostConstruct
    fun init() {
        val configured = config.models()
            .filter { it.enabled() }
            .associate { entry ->
                entry.id() to ModelDescriptor(
                    id = entry.id(),
                    provider = entry.provider(),
                    endpoint = entry.endpoint().orElse(null),
                    sensitivity = parseSensitivity(entry.sensitivity()),
                    enabled = entry.enabled(),
                )
            }
        // Sandbox convenience: with no models configured, seed a mock so the service still boots.
        registry = configured.ifEmpty {
            mapOf(config.defaultModel() to ModelDescriptor(id = config.defaultModel(), provider = "mock"))
        }
        providersByKey = providers.associateBy { it.key }
        log.infof(
            "copilot model gateway ready: models=%s providers=%s default=%s",
            registry.keys,
            providersByKey.keys,
            config.defaultModel(),
        )
    }

    fun availableModels(): List<ModelDescriptor> = registry.values.toList()

    fun defaultModelId(): String = config.defaultModel()

    /**
     * Run a streaming completion; calls [onChunk] for each text token as it arrives. Behaves like
     * [complete] for routing and audit — the audit event fires AFTER the last chunk so usage stats
     * (token counts) are available. Tool-call rounds emit no text; [onChunk] is silent for them.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun completeStream(
        modelId: String?,
        request: ModelRequest,
        sensitive: Boolean = false,
        actorId: String,
        onChunk: suspend (String) -> Unit,
    ): ModelResponse {
        val descriptor = resolve(modelId, sensitive)
        val provider = providersByKey[descriptor.provider]
            ?: error("no ModelProvider registered for key '${descriptor.provider}' (model '${descriptor.id}')")
        val effective = request.copy(model = descriptor.id)
        return try {
            val response = provider.completeStream(descriptor, effective, onChunk)
            audit(descriptor, effective, response, AuditResult.SUCCESS, actorId)
            response
        } catch (e: Exception) {
            log.warnf(e, "streaming model completion failed: model=%s provider=%s", descriptor.id, descriptor.provider)
            audit(descriptor, effective, null, AuditResult.FAILURE, actorId)
            throw e
        }
    }

    /**
     * Run a completion. [sensitive] forces self-hosted routing (customer PII / money-path context).
     * A backing-provider failure is surfaced as an audited [AuditResult.FAILURE]; the caller decides
     * how to degrade. [actorId] attributes the audit event to the customer the assistant acts for.
     */
    // Deliberately broad: a model/provider backend can fail many ways (HTTP, timeout, parse, quota);
    // we audit the FAILURE and rethrow so the caller degrades gracefully. Catching narrowly would let
    // an unclassified failure escape unaudited.
    @Suppress("TooGenericExceptionCaught")
    suspend fun complete(
        modelId: String?,
        request: ModelRequest,
        sensitive: Boolean = false,
        actorId: String,
    ): ModelResponse {
        val descriptor = resolve(modelId, sensitive)
        val provider = providersByKey[descriptor.provider]
            ?: error("no ModelProvider registered for key '${descriptor.provider}' (model '${descriptor.id}')")

        val effective = request.copy(model = descriptor.id)
        return try {
            val response = provider.complete(descriptor, effective)
            audit(descriptor, effective, response, AuditResult.SUCCESS, actorId)
            response
        } catch (e: Exception) {
            log.warnf(e, "model completion failed: model=%s provider=%s", descriptor.id, descriptor.provider)
            audit(descriptor, effective, null, AuditResult.FAILURE, actorId)
            throw e
        }
    }

    private fun resolve(modelId: String?, sensitive: Boolean): ModelDescriptor {
        val requested = modelId?.takeIf { it.isNotBlank() } ?: config.defaultModel()
        val descriptor = registry[requested]
            ?: error("unknown model '$requested' — not in copilot.model-gateway.models")
        if (sensitive && descriptor.sensitivity != Sensitivity.SELF_HOSTED) {
            val selfHosted = registry.values.firstOrNull { it.sensitivity == Sensitivity.SELF_HOSTED }
                ?: error("sensitive request but no self-hosted model registered (ADR-0089 D6 routing)")
            log.infof("sensitive routing: %s -> %s", descriptor.id, selfHosted.id)
            return selfHosted
        }
        return descriptor
    }

    private suspend fun audit(
        descriptor: ModelDescriptor,
        request: ModelRequest,
        response: ModelResponse?,
        result: AuditResult,
        actorId: String,
    ) {
        auditPublisher.publish(
            AuditEvent(
                actorId = actorId,
                actorType = "AI_AGENT",
                operation = "copilot.model.complete",
                resourceType = "llm.model",
                resourceId = descriptor.id,
                result = result,
                payload = mapOf(
                    "model_id" to descriptor.id,
                    "model_provider" to descriptor.provider,
                    "model_version" to (response?.modelVersion ?: "unknown"),
                    "sensitivity" to descriptor.sensitivity.name,
                    "prompt_hash" to promptHash(request),
                    "stop_reason" to (response?.stopReason?.name ?: "ERROR"),
                    "input_tokens" to (response?.usage?.inputTokens ?: 0),
                    "output_tokens" to (response?.usage?.outputTokens ?: 0),
                ),
            ),
        )
    }

    /** SHA-256 over the prompt — provenance without storing raw (possibly PII) content. */
    private fun promptHash(request: ModelRequest): String {
        val material = request.messages.joinToString("\n") { "${it.role}:${it.content}" }
        return MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun parseSensitivity(raw: String): Sensitivity = when (raw.trim().lowercase()) {
        "self-hosted", "self_hosted", "selfhosted" -> Sensitivity.SELF_HOSTED
        else -> Sensitivity.HOSTED
    }
}
