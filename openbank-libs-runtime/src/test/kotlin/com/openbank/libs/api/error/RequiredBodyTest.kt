// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.api.error

import jakarta.ws.rs.PathParam
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.UriInfo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The whole safety of these guards rests on [RequiredBody.entityParameter] and
 * [RequiredBody.isRequired] being exactly right, because they run on **every** POST/PUT/PATCH in
 * **every** service. A false positive rejects valid money-path traffic, which is strictly worse
 * than the 500 being fixed — so the negative cases below matter more than the positive one.
 *
 * The `suspend` cases are the reason this file exists. A `suspend fun` carries a
 * `kotlin.coroutines.Continuation` at the JVM level, unannotated, and an earlier draft of the
 * entity detector treated it as the request body. The fleet has 346 suspend handlers; that draft
 * would have made every body-less POST answer 400.
 */
class RequiredBodyTest {

    data class Payload(val value: String)

    /**
     * PUBLIC on purpose. Kotlin can omit `@NotNull` on parameters of non-public declarations, and
     * production handlers are public resource classes — a private fixture would be testing a
     * different shape from the one that runs, which is how a guard passes its own tests and fails
     * in production.
     */
    @Suppress("UNUSED_PARAMETER")
    class Handlers {
        // Non-nullable entity: Kotlin emits @NotNull on the JVM parameter.
        fun requiredBody(request: Payload) = Unit

        // Nullable entity: the handler opted into handling null itself (BillingResource.reverse).
        fun optionalBody(request: Payload?) = Unit

        // No entity at all — a trigger endpoint.
        fun noBody(@PathParam("id") id: String) = Unit

        // Only JAX-RS-annotated and container-injected parameters.
        fun onlyParams(@PathParam("id") id: String, @QueryParam("q") q: String?, uriInfo: UriInfo) = Unit

        // suspend + entity: the Continuation must not be mistaken for the body.
        suspend fun suspendWithBody(request: Payload) = Unit

        // suspend + NO entity: the case an earlier draft would have wrongly rejected.
        suspend fun suspendNoBody(@PathParam("id") id: String) = Unit
    }

    private fun method(name: String) = Handlers::class.java.declaredMethods.first { it.name == name }

    @Nested
    inner class EntityDetection {

        @Test
        fun `finds the entity parameter on a plain handler`() {
            assertThat(RequiredBody.entityParameter(method("requiredBody"))).isNotNull()
        }

        @Test
        fun `finds it on a suspend handler without mistaking the Continuation`() {
            val p = RequiredBody.entityParameter(method("suspendWithBody"))

            assertThat(p).isNotNull()
            assertThat(p!!.type.name).isNotEqualTo("kotlin.coroutines.Continuation")
            assertThat(p.type.name).isEqualTo(Payload::class.java.name)
        }

        /** The dangerous one: 346 suspend handlers in the fleet, most taking no body. */
        @Test
        fun `reports NO entity for a suspend handler that takes none`() {
            assertThat(RequiredBody.entityParameter(method("suspendNoBody"))).isNull()
        }

        @Test
        fun `reports no entity when every parameter is annotated or container-injected`() {
            assertThat(RequiredBody.entityParameter(method("noBody"))).isNull()
            assertThat(RequiredBody.entityParameter(method("onlyParams"))).isNull()
        }
    }

    @Nested
    inner class Nullability {

        @Test
        fun `a non-nullable body parameter is required`() {
            val p = RequiredBody.entityParameter(method("requiredBody"))!!

            assertThat(RequiredBody.isRequired(p.annotations)).isTrue()
        }

        /**
         * The counterexample that shaped the design. `BillingResource.reverse` declares
         * `ReverseFeeRequest?` and returns its own 400 naming the missing fields; a blanket rule
         * would pre-empt that and decide a per-handler question globally.
         */
        @Test
        fun `a nullable body parameter is NOT required and keeps its own handling`() {
            val p = RequiredBody.entityParameter(method("optionalBody"))!!

            assertThat(RequiredBody.isRequired(p.annotations)).isFalse()
        }

        @Test
        fun `no annotations at all means not required — degrade to today's behaviour`() {
            assertThat(RequiredBody.isRequired(emptyArray())).isFalse()
        }
    }

    @Nested
    inner class MethodScope {

        @Test
        fun `only POST PUT and PATCH carry a body`() {
            assertThat(RequiredBody.BODY_METHODS).containsExactlyInAnyOrder("POST", "PUT", "PATCH")
            assertThat(RequiredBody.BODY_METHODS).doesNotContain("GET", "DELETE", "HEAD", "OPTIONS")
        }
    }
}
