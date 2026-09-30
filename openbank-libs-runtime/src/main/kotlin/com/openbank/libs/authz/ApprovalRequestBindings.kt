// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.authz

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openbank.libs.approval.ApprovalRequestBinding
import com.openbank.libs.idempotency.RequestFingerprint
import com.openbank.libs.idempotency.RequestFingerprints
import io.quarkus.security.identity.SecurityIdentity
import jakarta.interceptor.InvocationContext
import java.io.File
import java.io.InputStream
import java.io.Reader
import java.nio.channels.Channel
import java.nio.file.Path
import kotlin.coroutines.Continuation
import kotlin.reflect.jvm.kotlinFunction

/**
 * Binds a four-eyes approval to the request it was issued for (ADR-0155).
 *
 * The fingerprint is a [RequestFingerprint] over the intercepted Java method (`Class#method`, so
 * two endpoints guarded by the same action never share an approval) and the canonical JSON
 * ([RequestFingerprints]) of every business argument the method receives, keyed by parameter name.
 * Path and query parameters are method arguments, so a request with no body is defined by them;
 * a JSON body arrives as its deserialised DTO. Fingerprinting the arguments rather than the raw
 * bytes binds exactly what the method will execute on: two bodies that deserialise to the same
 * DTO are the same request, and a field the DTO ignores cannot make them differ.
 *
 * Framework-supplied context arguments (the coroutine continuation, JAX-RS context objects, the
 * security identity) are not part of the request and are skipped. An argument that cannot be
 * canonicalised — a stream, a file upload, a reactive publisher, or anything Jackson cannot
 * serialise — makes the request unbindable, and [of] throws: four-eyes on such an endpoint
 * refuses the call rather than issuing an approval it could not later hold to its content.
 */
internal object ApprovalRequestBindings {

    /** Upper bound on [ApprovalRequestBinding.summary]; the domain type enforces the same limit. */
    const val MAX_SUMMARY_LENGTH = ApprovalRequestBinding.MAX_SUMMARY_LENGTH

    private val mapper: ObjectMapper = ObjectMapper().findAndRegisterModules()

    private val SENSITIVE_TOKENS = setOf(
        "password", "passphrase", "passwd", "secret", "token", "credential", "credentials",
        "pin", "cvv", "cvc", "otp", "pan", "apikey", "privatekey", "cardnumber",
    )
    private val KEY_TOKEN_SPLIT = Regex("(?<=[a-z0-9])(?=[A-Z])|[_\\-. ]+")
    private val CONTROL_CHARS = Regex("\\p{Cntrl}")

    /** Thrown when the intercepted call has an argument that cannot be bound. */
    class UnbindableRequestException(message: String) : RuntimeException(message)

    fun of(ctx: InvocationContext, action: String, resourceId: String?): ApprovalRequestBinding {
        val method = ctx.method
        val target = "${method.declaringClass.name}#${method.name}"
        val names = method.kotlinFunction?.parameters?.drop(1)?.map { it.name }
        val arguments = linkedMapOf<String, Any?>()
        ctx.parameters.forEachIndexed { index, value ->
            val name = names?.getOrNull(index) ?: "arg$index"
            when {
                value == null -> arguments[name] = null
                isContext(value) -> Unit
                isUnbindable(value) -> throw UnbindableRequestException(
                    "four-eyes action '$action' cannot be bound to an approval: argument '$name' is a " +
                        "${value.javaClass.simpleName}, whose content cannot be fingerprinted",
                )
                else -> arguments[name] = value
            }
        }
        val canonical = try {
            RequestFingerprints.canonical(mapper, arguments)
        } catch (@Suppress("TooGenericExceptionCaught") ex: Exception) {
            throw UnbindableRequestException(
                "four-eyes action '$action' cannot be bound to an approval: arguments are not serialisable " +
                    "(${ex.javaClass.simpleName})",
            )
        }
        return ApprovalRequestBinding(
            fingerprint = RequestFingerprint.of("INVOKE", target, canonical),
            summary = summary(action, target, resourceId, canonical),
        )
    }

    /**
     * What the checker is shown: action, endpoint, resource and the arguments with credential-like
     * fields redacted, control characters flattened, capped at [MAX_SUMMARY_LENGTH].
     */
    private fun summary(action: String, target: String, resourceId: String?, canonical: String): String {
        val args = if (canonical.isEmpty()) "{}" else mapper.writeValueAsString(redact(mapper.readTree(canonical)))
        val text = "action=$action endpoint=${target.substringAfterLast('.')} resource=${resourceId ?: "-"} args=$args"
        val flat = CONTROL_CHARS.replace(text, " ")
        return if (flat.length <= MAX_SUMMARY_LENGTH) flat else flat.take(MAX_SUMMARY_LENGTH - 1) + "…"
    }

    private fun redact(node: JsonNode): JsonNode = when {
        node.isObject -> ObjectNode(JsonNodeFactory.instance).also { out ->
            node.properties().forEach { (k, v) ->
                out.set<JsonNode>(k, if (isSensitiveKey(k)) JsonNodeFactory.instance.textNode("***") else redact(v))
            }
        }
        node.isArray -> ArrayNode(JsonNodeFactory.instance).also { out -> node.forEach { out.add(redact(it)) } }
        else -> node
    }

    /** Token-wise, so `pin` redacts `pin`/`newPin` but not `shipping`, and `pan` not `company`. */
    private fun isSensitiveKey(key: String): Boolean {
        val tokens = key.split(KEY_TOKEN_SPLIT).filter { it.isNotEmpty() }.map { it.lowercase() }
        val joined = tokens.joinToString("")
        return tokens.any { it in SENSITIVE_TOKENS } || SENSITIVE_TOKENS.any { it.length > 5 && joined.endsWith(it) }
    }

    private fun isContext(value: Any): Boolean {
        if (value is Continuation<*> || value is SecurityIdentity || value is java.security.Principal) return true
        val name = value.javaClass.name
        return CONTEXT_PACKAGES.any { name.startsWith(it) } ||
            value.javaClass.interfaces.any { i -> CONTEXT_PACKAGES.any { i.name.startsWith(it) } }
    }

    private fun isUnbindable(value: Any): Boolean {
        if (value is InputStream || value is Reader || value is File || value is Path || value is Channel) return true
        val types = generateSequence<Class<*>>(value.javaClass) { it.superclass }.flatMap { c ->
            sequenceOf(c) + c.interfaces.asSequence()
        }
        return types.any { t -> UNBINDABLE_TYPE_NAMES.any { t.name.endsWith(it) } }
    }

    private val CONTEXT_PACKAGES = listOf(
        "jakarta.ws.rs.core.",
        "jakarta.ws.rs.container.",
        "jakarta.ws.rs.sse.",
        "io.vertx.core.http.",
        "io.vertx.ext.web.",
        "io.vertx.mutiny.core.http.",
        "io.vertx.mutiny.ext.web.",
        "org.jboss.resteasy.reactive.server.",
    )

    private val UNBINDABLE_TYPE_NAMES = listOf(
        ".FileUpload",
        ".EntityPart",
        ".MultipartFormDataInput",
        "org.reactivestreams.Publisher",
        "java.util.concurrent.Flow\$Publisher",
    )
}
