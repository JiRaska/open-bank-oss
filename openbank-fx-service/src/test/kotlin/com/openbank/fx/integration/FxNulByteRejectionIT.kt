// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * `POST /api/v1/fx/convert` answered **500** for a NUL character (U+0000) in a body string (#5913).
 *
 * All eight of fx-service's occurrences in that run were this one operation; the fuzzer's value
 * landed in `toCurrency`, which reached Postgres as the `quote_currency=$2` bind of the rate lookup
 * and produced `invalid byte sequence for encoding "UTF8": 0x00` (SQLState 22021) as an
 * `org.hibernate.exception.DataException`. `GET /api/v1/fx/rates/{base}/{quote}` produced none, so
 * the body is the only measured carrier here.
 *
 * The response was a well-formed `INTERNAL_ERROR` document, so the assertion is on the status.
 *
 * The fix is the shared boundary in `openbank-libs-runtime`
 * (`com.openbank.libs.api.error.NulByteGuards`) rather than anything in this service — which makes
 * this IT the check that the guard is discovered and registered inside a *running* Quarkus app, a
 * thing no unit test over the guard classes can establish.
 *
 * **Falsification (run, not assumed):** with `NulByte.contains` forced to return false, this test
 * answers 500 — the exact status and cause #5913 reported. The paired "does not reject everything"
 * control lives in `TransactionNulByteRejectionIT` (a clean query string that must not be rejected)
 * and in `NulByteGuardsTest`; it is deliberately not duplicated here, because `fx.convert` with a
 * currency pair that has no stored rate answers 400 on its own, so a status-only control on this
 * endpoint would discriminate nothing.
 */
@QuarkusTest
@QuarkusTestResource(FxNulByteRejectionIT.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.fx.it.PostgresRedisTestResource::class)
class FxNulByteRejectionIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = InMemoryConnector.switchOutgoingChannelsToInMemory("fx-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    /** The JSON escape for U+0000 as it travels on the wire: six ASCII characters, legal JSON. */
    private val escapedNul = "\\u0000"

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `convert rejects a NUL in the toCurrency body field with 400`() {
        val payload = """
            {
              "partyId": "${UUID.randomUUID()}",
              "accountId": null,
              "partyName": "Jan Novak",
              "fromCurrency": "CZK",
              "toCurrency": "${escapedNul}EUR",
              "fromAmountMinorUnits": 100000
            }
        """.trimIndent()

        Given {
            contentType("application/json")
            header("Idempotency-Key", UUID.randomUUID().toString())
            body(payload)
        } When {
            post("/api/v1/fx/convert")
        } Then {
            statusCode(400)
        }
    }
}
