// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.document.infrastructure.rest

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.junit.jupiter.api.Test
import java.util.UUID

/** Proves the disclosure-content boundary rejects an anonymous request before revealing existence. */
@QuarkusTest
@QuarkusTestResource(com.openbank.document.it.PostgresRedisTestResource::class)
class DisclosureSnapshotContentSecurityIT {

    @Test
    fun `anonymous disclosure snapshot read is rejected with 401`() {
        given()
            .header("X-Expected-SHA256", "a".repeat(64))
            .get("/api/v1/documents/disclosure-snapshots/${UUID.randomUUID()}/content")
            .then()
            .statusCode(401)
    }
}
