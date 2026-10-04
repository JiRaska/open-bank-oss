// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.warmup

import com.fasterxml.jackson.databind.ObjectMapper
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

class ResourceTypeWarmupTest {
    data class Dto(val id: String, val nested: Nested)
    data class Nested(val amount: java.math.BigDecimal)
    data class Command(val note: String)

    @Path("/x")
    @Suppress("UnusedParameter", "FunctionOnlyReturningConstant")
    class Resource {
        @GET fun uni(): Uni<Dto> = Uni.createFrom().nullItem()

        @GET suspend fun suspended(@PathParam("id") id: String): List<Dto> = emptyList()

        @POST fun create(body: Command): Response = Response.ok().build()

        @GET fun text(): String = ""

        fun notAnEndpoint(): Nested? = null
    }

    class NotAResource

    private fun Type.render(): String = when (this) {
        is Class<*> -> simpleName
        is ParameterizedType -> (rawType as Class<*>).simpleName +
            actualTypeArguments.joinToString(",", "<", ">") { it.render() }
        else -> typeName
    }

    @Test
    fun `finds only @Path classes`() {
        assertThat(ResourceTypeWarmup.resourceClasses(listOf(Resource::class.java, NotAResource::class.java)))
            .containsExactly(Resource::class.java)
    }

    @Test
    fun `unwraps Uni and suspend continuations, takes bodies, skips opaque types and non-endpoints`() {
        val (out, inp) = ResourceTypeWarmup.endpointTypes(Resource::class.java)
        assertThat(out.map { it.render() }).containsExactlyInAnyOrder("Dto", "List<Dto>")
        assertThat(inp.map { it.render() }).containsExactly("Command")
    }

    @Test
    fun `builds the serializers without an instance`() {
        val detail = ResourceTypeWarmup.warm(ObjectMapper(), listOf(Resource::class.java))
        assertThat(detail).isEqualTo("1 resources, 3 (de)serializers built, 0 unbuildable")
    }
}
