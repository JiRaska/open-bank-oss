// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.api.error

import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.container.ResourceInfo
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.ext.Provider
import jakarta.ws.rs.ext.ReaderInterceptor
import jakarta.ws.rs.ext.ReaderInterceptorContext
import java.lang.reflect.Method
import java.lang.reflect.Parameter
import kotlin.reflect.KParameter
import kotlin.reflect.jvm.kotlinFunction

/**
 * Turns a **missing or `null` JSON request body into 400 instead of 500**, fleet-wide.
 *
 * ## The defect
 *
 * A resource method declares a non-nullable Kotlin body parameter (`request: FooRequest`). Kotlin
 * null-safety is a *compile-time* property — JAX-RS/Jackson hands a `null` straight through at
 * runtime, the first field access throws NPE, and the generic mapper renders it as 500.
 *
 * The first full run of the authenticated fuzz lane found **28 of these across 8 money-path
 * services** (#3038) — `POST /api/v1/balances/{accountId}/credit`, `POST /api/v1/swift`,
 * `POST /api/v1/fx/convert` among them. Twenty were exactly `-d null` or a body-less POST.
 *
 * ## The decision that makes this safe: read Kotlin's own nullability
 *
 * A blanket "null body → 400" rule would be wrong, and provably so. `BillingResource.reverse`
 * declares `request: ReverseFeeRequest?` and *deliberately* handles null, returning its own 400
 * naming both missing fields. A global rule would pre-empt that with a generic message, and more
 * importantly would be deciding a per-handler question globally.
 *
 * So these guards decide per parameter, from the same source of truth the bug comes from: the
 * handler's own Kotlin nullability. A body is required **iff the handler said it was**.
 *
 * Read via `kotlinFunction`, NOT via `@org.jetbrains.annotations.NotNull`. The compiler does emit
 * that annotation, but it is declared `@Retention(RetentionPolicy.CLASS)` — it exists in the class
 * file and is invisible to `Parameter.getAnnotations()` at runtime, so an annotation-based check
 * can never return true. The first version of this guard was written that way and its own unit
 * test caught it. `kotlinFunction` reads the `@Metadata` annotation, which is RUNTIME-retained;
 * `AuthorizeInterceptor` and `FeatureFlagInterceptor` already depend on it fleet-wide.
 *
 * ## Why two classes
 *
 * The two shapes are knowable at different moments, and merging them would be a correctness bug:
 *
 * - **Absent body** is decidable from headers ([ContainerRequestContext.hasEntity]) — cheap and
 *   non-blocking, so it belongs in a request filter. The reader is never invoked in this case, so
 *   an interceptor could not see it at all.
 * - **A literal `null` body** is only knowable after deserialization. Detecting it in a request
 *   filter would mean reading and re-buffering the entity stream, which on RESTEasy **Reactive**
 *   is a blocking read on the I/O thread. A [ReaderInterceptor] runs where blocking is already
 *   permitted and sees the decoded value directly — `proceed()` returning `null` IS the defect.
 *
 * ## This is a net, not a cure
 *
 * The handlers still declare non-nullable parameters. This stops the 500 reaching a client, and
 * covers endpoints nobody has fuzzed yet, but the per-handler fix (nullable parameter +
 * `requireNotNull`, as #3032 did for the sixteen approval resources) remains the actual
 * correction. Defence in depth, not a reason to close #3038.
 */
internal object RequiredBody {

    val BODY_METHODS = setOf("POST", "PUT", "PATCH")

    private const val CONTINUATION = "kotlin.coroutines.Continuation"

    /**
     * The entity parameter of [method], or null when it declares none.
     *
     * Three exclusions, each load-bearing:
     * - **JAX-RS-annotated** parameters (`@PathParam`, `@QueryParam`, `@Context`, `@BeanParam`, …)
     *   are never the entity.
     * - **`kotlin.coroutines.Continuation`** — every `suspend fun` carries one at the JVM level, and
     *   it is unannotated. Without this exclusion the fleet's 346 suspend handlers would each look
     *   like they expect a body, and every body-less POST would start answering 400. This is the
     *   single most dangerous mistake available here.
     * - **`jakarta.ws.rs.core.*`** container types (`UriInfo`, `HttpHeaders`, …).
     */
    fun entityParameter(method: Method): Parameter? = method.parameters.firstOrNull { p ->
        val jaxRsAnnotated = p.annotations.any {
            it.annotationClass.qualifiedName?.startsWith("jakarta.ws.rs.") == true
        }
        !jaxRsAnnotated &&
            p.type.name != CONTINUATION &&
            !p.type.name.startsWith("jakarta.ws.rs.core.")
    }

    /**
     * True only when the handler declared the body **non-nullable** — i.e. null is a defect here.
     *
     * A Java handler, or any method whose Kotlin metadata cannot be read, returns false: this guard
     * only ever acts on an intent it can actually see.
     *
     * The index mapping is the subtle part. `KFunction.parameters` excludes the `Continuation` that
     * every `suspend fun` carries at the JVM level, and includes the instance/extension receivers,
     * so JVM position and Kotlin position do not line up. Both sides are filtered to value
     * parameters before pairing.
     */
    fun isRequired(method: Method, parameter: Parameter): Boolean {
        val kFunction = runCatching { method.kotlinFunction }.getOrNull() ?: return false
        val jvmValueParams = method.parameters.filter { it.type.name != CONTINUATION }
        val index = jvmValueParams.indexOf(parameter).takeIf { it >= 0 } ?: return false
        val kParam = kFunction.parameters
            .filter { it.kind == KParameter.Kind.VALUE }
            .getOrNull(index) ?: return false
        return !kParam.type.isMarkedNullable
    }
}

/**
 * Rejects a POST/PUT/PATCH carrying **no entity at all** when the matched handler declares a
 * non-nullable body parameter.
 *
 * Post-matching by design — it needs [ResourceInfo] to know what the handler expects, so it must
 * not be `@PreMatching`. Reads no stream: [ContainerRequestContext.hasEntity] answers from headers,
 * which is what keeps it safe on the reactive stack.
 */
@Provider
class AbsentBodyRequestFilter : ContainerRequestFilter {

    @Context
    lateinit var resourceInfo: ResourceInfo

    override fun filter(requestContext: ContainerRequestContext) {
        if (requestContext.method !in RequiredBody.BODY_METHODS) return
        if (requestContext.hasEntity()) return

        val method = runCatching { resourceInfo.resourceMethod }.getOrNull() ?: return
        val entity = RequiredBody.entityParameter(method) ?: return
        if (!RequiredBody.isRequired(method, entity)) return

        throw BadRequestException("request body is required")
    }
}

/**
 * Rejects a body that deserialises to `null` — the JSON literal `null`, which Jackson decodes
 * happily and hands to a parameter Kotlin believes cannot hold it.
 *
 * A nullable parameter passes through untouched, so handlers that deliberately accept an absent
 * body keep their own behaviour and their own error messages.
 */
@Provider
class NullBodyReaderInterceptor : ReaderInterceptor {

    @Context
    lateinit var resourceInfo: ResourceInfo

    override fun aroundReadFrom(context: ReaderInterceptorContext): Any? {
        val value = context.proceed()
        if (value != null) return value

        // Same reason as the filter: `context.annotations` cannot answer this, because Kotlin's
        // nullability annotations are CLASS-retained. Go back to the matched method.
        val method = runCatching { resourceInfo.resourceMethod }.getOrNull() ?: return null
        val entity = RequiredBody.entityParameter(method) ?: return null
        if (RequiredBody.isRequired(method, entity)) {
            throw BadRequestException("request body is required")
        }
        return null
    }
}
