// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.integration

import com.openbank.libs.domain.error.ErrorCategory
import com.openbank.libs.domain.error.ErrorCode
import com.openbank.libs.domain.error.NotFound
import com.openbank.libs.domain.error.RateLimitExceeded
import com.openbank.libs.domain.error.requireParam
import com.openbank.notification.it.PostgresTestResource
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.annotation.security.PermitAll
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.not
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import java.time.Duration

private enum class ProbeErrorCode(override val category: ErrorCategory, override val title: String) : ErrorCode {
    PROBE_NOT_FOUND(ErrorCategory.NOT_FOUND, "The probe does not exist"),
    ;

    override val code: String get() = name
}

private const val INTERNAL_DETAIL = "no row in probe for tenant=7"

/**
 * Test-only endpoints that throw the ADR-0326 typed errors, so the REAL mapper chain answers them.
 * `@Produces` is what every real resource here declares; without it a `String` method negotiates as
 * `text/plain`, and the generic mappers (which set no media type) then render `ApiError` by
 * `toString()` — pre-existing behaviour, and not what this proof is about.
 */
@Path("/test/error-model")
@PermitAll
@Produces(MediaType.APPLICATION_JSON)
class ErrorModelProbeResource {
    @GET
    @Path("/not-found")
    fun notFound(): String =
        throw NotFound("Probe p-1 not found", ProbeErrorCode.PROBE_NOT_FOUND, internalDetail = INTERNAL_DETAIL)

    @GET
    @Path("/rate-limited")
    fun rateLimited(): String = throw RateLimitExceeded(retryAfter = Duration.ofSeconds(30))

    @GET
    @Path("/param")
    fun param(@QueryParam("date") date: String?): String = requireParam(date, "date")

    @GET
    @Path("/illegal-state")
    fun illegalState(): String = error("an internal invariant, reported today as the caller's fault")
}

/**
 * ADR-0326 slice 1, by effect: a mapper unit test proves what `toResponse` returns, never that the
 * mapper is REGISTERED in a running service or that its `@Context` injection works there. This
 * drives real HTTP through a real Quarkus application that merely depends on libs-runtime.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class DomainExceptionMapperIT {

    @Inject
    lateinit var registry: MeterRegistry

    @Test
    fun `a typed domain error is answered as the problem document, with the ApiError members intact`() {
        given()
            .`when`()
            .get("/test/error-model/not-found")
            .then()
            .statusCode(404)
            .contentType("application/json")
            // RFC 9457 members and the extensions
            .body("type", equalTo("urn:openbank:error:probe-not-found"))
            .body("title", equalTo("The probe does not exist"))
            .body("status", equalTo(404))
            .body("detail", equalTo("Probe p-1 not found"))
            .body("instance", startsWith("urn:openbank:error-occurrence:"))
            .body("code", equalTo("PROBE_NOT_FOUND"))
            .body("correlationId", notNullValue())
            .body("retryable", equalTo(false))
            // what an ApiError consumer reads today
            .body("traceId", notNullValue())
            .body("message", equalTo("Probe p-1 not found"))
            .body("timestamp", notNullValue())
            // the internal detail stays in the log
            .body(not(containsString("tenant=7")))
    }

    @Test
    fun `the media type is problem+json when the caller asks for it`() {
        // Content negotiation against the resource's @Produces runs BEFORE the method is invoked, so
        // an Accept naming ONLY problem+json is a 406 from the framework and the mapper never sees it.
        // A caller asks for the problem document by listing it beside a type the endpoint produces.
        given()
            .accept("application/problem+json, application/json")
            .`when`()
            .get("/test/error-model/not-found")
            .then()
            .statusCode(404)
            .contentType("application/problem+json")
            .body("code", equalTo("PROBE_NOT_FOUND"))
    }

    @Test
    fun `retry-after is a header and the body says retryable`() {
        given()
            .`when`()
            .get("/test/error-model/rate-limited")
            .then()
            .statusCode(429)
            .header("Retry-After", "30")
            .body("code", equalTo("RATE_LIMIT_EXCEEDED"))
            .body("retryable", equalTo(true))
    }

    @Test
    fun `a missing request parameter answers 400 through the typed path, naming the parameter`() {
        given()
            .`when`()
            .get("/test/error-model/param")
            .then()
            .statusCode(400)
            .body("code", equalTo("VALIDATION_ERROR"))
            .body("type", equalTo("urn:openbank:error:validation-error"))
            .body("violations[0].field", equalTo("date"))
            .body("details[0].field", equalTo("date"))
    }

    @Test
    fun `a raw IllegalStateException is answered exactly as before, and the firing is counted`() {
        fun fired() = registry.find("openbank.api.generic_exception_mapper.fired")
            .tag("mapped", "IllegalStateException").tag("status", "422").counters().sumOf { it.count() }
        val before = fired()

        given()
            .`when`()
            .get("/test/error-model/illegal-state")
            .then()
            .statusCode(422)
            .body("code", equalTo("BUSINESS_RULE_VIOLATION"))
            // unchanged behaviour: the old envelope, not the problem document
            .body("type", nullValue())

        // The counter landed in the registry Quarkus exports — not merely in some registry.
        assertThat(fired()).isEqualTo(before + 1.0)
    }

    @Test
    fun `typed errors are counted by code in the exported registry`() {
        fun counted() = registry.find("openbank.api.errors")
            .tag("code", "PROBE_NOT_FOUND").tag("status", "404").counters().sumOf { it.count() }
        val before = counted()

        given().`when`().get("/test/error-model/not-found").then().statusCode(404)

        assertThat(counted()).isEqualTo(before + 1.0)
    }
}
