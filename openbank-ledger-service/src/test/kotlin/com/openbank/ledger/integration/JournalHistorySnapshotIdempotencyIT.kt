// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** Real HTTP/Postgres wire contract. Snapshot data is deliberately raw, never classified economic truth. */
@QuarkusTest
@QuarkusTestResource(com.openbank.ledger.it.PostgresRedpandaTestResource::class)
@TestSecurity(user = "snapshot-reader", roles = ["ROLE_AUDITOR"])
class JournalHistorySnapshotIdempotencyIT {
    private val account = UUID.randomUUID()

    @Test
    fun `capture requires an idempotency key and replays the first snapshot for a retry`() {
        val body = mapOf(
            "accountId" to account,
            "currency" to "CZK",
            "fromDate" to "2026-10-01",
            "throughDate" to "2026-10-31",
        )
        given().contentType("application/json").body(body).post(PATH).then().statusCode(400)
        for (invalid in listOf("", "x".repeat(129), "invalid key")) {
            given().contentType("application/json").body(body).header("Idempotency-Key", invalid)
                .post(PATH).then().statusCode(400)
        }
        val key = UUID.randomUUID().toString()
        val first = given().contentType("application/json").body(body).header("Idempotency-Key", key)
            .post(PATH).then().extract()
        val retry = given().contentType("application/json").body(body).header("Idempotency-Key", key)
            .post(PATH).then().statusCode(first.statusCode()).header("X-Idempotency-Replayed", "true").extract()
        assertThat(retry.jsonPath().getString("snapshotId")).isEqualTo(first.jsonPath().getString("snapshotId"))
        given().contentType("application/json").body(body + ("throughDate" to "2026-10-30"))
            .header("Idempotency-Key", key).post(PATH).then().statusCode(409)
    }

    private companion object {
        const val PATH = "/api/v1/journals/history-snapshots"
    }
}
