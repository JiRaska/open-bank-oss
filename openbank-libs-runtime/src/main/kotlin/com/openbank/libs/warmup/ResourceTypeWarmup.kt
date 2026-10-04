// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.warmup

import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.ws.rs.HttpMethod
import jakarta.ws.rs.Path
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType

/**
 * Builds the Jackson (de)serializers of every REST resource's request and response types before
 * the first request does (#11890 follow-up).
 *
 * Measured on lending-service after the first warm-up shipped: the first `GET loan-book` still
 * took 1.8 s, ~0.9 s of it AFTER the last SQL statement returned — first-use construction of the
 * response DTO's serializer graph (Kotlin-module introspection of every nested data class). A
 * generic payload round-trip warms Jackson itself, not the per-type serializer cache, which is
 * keyed by type. `ObjectMapper.writerFor`/`readerFor` prefetch the root (de)serializer, and bean
 * (de)serializers resolve their property graph on construction, so one call per type is enough.
 * No instance is needed, which is what makes this generic.
 */
internal object ResourceTypeWarmup {
    private val WRAPPERS = setOf(
        "io.smallrye.mutiny.Uni",
        "io.smallrye.mutiny.Multi",
        "java.util.concurrent.CompletionStage",
        "java.util.concurrent.CompletableFuture",
        "org.jboss.resteasy.reactive.RestResponse",
        "java.util.Optional",
    )
    private val OPAQUE = setOf(
        "jakarta.ws.rs.core.Response",
        "java.lang.Object",
        "java.lang.Void",
        "kotlin.Unit",
        "java.lang.String",
        "void",
    )
    private const val CONTINUATION = "kotlin.coroutines.Continuation"

    /** Resource classes = beans whose class carries `@Path` (RESTEasy Reactive makes them beans). */
    fun resourceClasses(beanClasses: Collection<Class<*>>): List<Class<*>> =
        beanClasses.filter { bean -> bean.annotations.any { it.annotationClass.java == Path::class.java } }.distinct()

    /** The (response, request-body) types of every HTTP-method endpoint on [resource]. */
    fun endpointTypes(resource: Class<*>): Pair<Set<Type>, Set<Type>> {
        val out = linkedSetOf<Type>()
        val inp = linkedSetOf<Type>()
        resource.methods.filter { it.isEndpoint() }.forEach { m ->
            responseType(m)?.let(::unwrap)?.takeUnless(::isOpaque)?.let(out::add)
            m.parameters.forEachIndexed { i, p ->
                val type = m.genericParameterTypes[i]
                // A body parameter is the one parameter with no annotation (JAX-RS rule).
                if (p.annotations.isEmpty() && rawName(type) != CONTINUATION) {
                    unwrap(type).takeUnless(::isOpaque)?.let(inp::add)
                }
            }
        }
        return out to inp
    }

    /** Prefetches every type's (de)serializer; returns the number of types it could build. */
    fun warm(mapper: ObjectMapper, beanClasses: Collection<Class<*>>): String {
        val out = linkedSetOf<Type>()
        val inp = linkedSetOf<Type>()
        val resources = resourceClasses(beanClasses)
        resources.forEach { r ->
            endpointTypes(r).let { (o, i) ->
                out += o
                inp += i
            }
        }
        var built = 0
        var failed = 0
        out.forEach { t -> if (attempt { mapper.writerFor(javaType(mapper, t)) }) built++ else failed++ }
        inp.forEach { t -> if (attempt { mapper.readerFor(javaType(mapper, t)) }) built++ else failed++ }
        return "${resources.size} resources, $built (de)serializers built, $failed unbuildable"
    }

    private fun attempt(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
        false
    }

    private fun javaType(mapper: ObjectMapper, t: Type): JavaType = mapper.typeFactory.constructType(t)

    private fun Method.isEndpoint(): Boolean = Modifier.isPublic(modifiers) &&
        !isSynthetic &&
        !isBridge &&
        annotations.any { annotation ->
            annotation.annotationClass.java.annotations.any { it.annotationClass.java == HttpMethod::class.java }
        }

    /** Kotlin `suspend fun x(): T` compiles to `x(Continuation<? super T>): Object`. */
    private fun responseType(m: Method): Type? {
        val last = m.genericParameterTypes.lastOrNull()
        if (last is ParameterizedType && rawName(last) == CONTINUATION) {
            val arg = last.actualTypeArguments.first()
            return if (arg is WildcardType) arg.lowerBounds.firstOrNull() ?: arg.upperBounds.firstOrNull() else arg
        }
        return m.genericReturnType
    }

    internal fun unwrap(t: Type): Type {
        var cur = t
        while (cur is ParameterizedType && rawName(cur) in WRAPPERS) {
            val arg = cur.actualTypeArguments.first()
            cur = if (arg is WildcardType) arg.upperBounds.first() else arg
        }
        return cur
    }

    private fun isOpaque(t: Type): Boolean = rawName(t) in OPAQUE

    private fun rawName(t: Type): String = when (t) {
        is Class<*> -> t.name
        is ParameterizedType -> (t.rawType as Class<*>).name
        else -> t.typeName
    }
}
