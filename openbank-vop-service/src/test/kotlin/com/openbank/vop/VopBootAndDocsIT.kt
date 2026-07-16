// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.vop

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItems
import org.junit.jupiter.api.Test

/**
 * Boots the service against a real Postgres and checks the two things unit tests cannot see:
 *
 * 1. **It boots at all** — Flyway runs V1, Hibernate validates `VopVerificationEntity` against the
 *    schema, the JDBC driver loads. This is the "released but never booted" defect class (missing
 *    runtime driver, duplicate config key, broken migration).
 * 2. **The bundled docs are actually served** (ADR-0019 Docs-as-Service). Without this, 14 markdown
 *    files are shipped on the assumption that the runtime picks them up — the same faith-based
 *    shipping the docs themselves warn against. The admin UI's Service Docs page reads this endpoint.
 *
 * `/q/...` is served on the main HTTP port here because `%test` sets
 * `quarkus.management.enabled: false`.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class VopBootAndDocsIT {

    @Test
    fun `application boots and reports ready against a live database`() {
        given()
            .`when`().get("/q/health/ready")
            .then()
            .statusCode(200)
            .body("status", equalTo("UP"))
    }

    @Test
    fun `every bundled doc is published in both languages`() {
        val slugs =
            listOf("index", "01-overview", "02-architecture", "03-api", "04-data", "05-operations", "06-compliance")

        listOf("en", "cs").forEach { lang ->
            given()
                .queryParam("lang", lang)
                .`when`().get("/q/openbank/docs")
                .then()
                .statusCode(200)
                .body("service", equalTo("openbank-vop-service"))
                // README normalises to slug `index` (DocsCatalog), so a file named README.<lang>.md
                // that failed to load would show up here as a missing `index`.
                .body("items.slug", hasItems(*slugs.toTypedArray()))
        }
    }

    @Test
    fun `a doc is served in the requested language, not silently fallen back`() {
        // DocsCatalog falls back requested -> language-agnostic -> en -> cs -> any, so a MISSING cs
        // file still returns 200 with English content. Asserting the status code would pass on a
        // fallback; only the content proves the cs file actually loaded.
        given()
            .queryParam("lang", "cs")
            .`when`().get("/q/openbank/docs/01-overview")
            .then()
            .statusCode(200)
            .body(containsString("Co služba dělá"))

        given()
            .queryParam("lang", "en")
            .`when`().get("/q/openbank/docs/01-overview")
            .then()
            .statusCode(200)
            .body(containsString("What this service does"))
    }
}
