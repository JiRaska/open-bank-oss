// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.e2e

import com.openbank.fx.domain.screening.ScreeningMatchStatus
import com.openbank.fx.domain.screening.ScreeningResult
import com.openbank.fx.domain.screening.ScreeningRole
import com.openbank.fx.infrastructure.client.SanctionsScreeningAdapter
import io.mockk.coEvery
import io.mockk.mockk
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.response.Response
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * End-to-end journey for a customer converting CZK into EUR: **look up the rate the bank is
 * quoting, execute the conversion at that rate, read the settled conversion back, and confirm the
 * rate that was actually applied is the one that was quoted** — plus the replay behaviour a client
 * relies on when it retries.
 *
 * Driven through the real HTTP surface (`@QuarkusTest` + RestAssured + `@TestSecurity`) against a
 * real Postgres (Flyway applied, seeding the rate table) and Valkey via `PostgresRedisTestResource`.
 *
 * ### The one stubbed edge, stated explicitly
 *
 * Sanctions screening is a call OUT of this service to sanctions-service, which is not running
 * here. It is stubbed to CLEAR (`QuarkusMock` over [SanctionsScreeningAdapter]) — and this journey
 * therefore makes NO claim about the screening hop itself. The stub is not convenience: the use
 * case fails CLOSED, so an unreachable sanctions-service routes every conversion to HOLD, which
 * settles nothing, and the journey would then assert over a state it was not written for while
 * still looking green. Fraud scoring, by contrast, fails open by design (#4221) and runs after the
 * write, so it is left alone. The outbound Kafka channel is switched to the in-memory connector:
 * the outbox ROW is this service's own state and is not asserted here, but the DISPATCH to a
 * broker is out of scope and is not claimed to have happened.
 *
 * ### Why the rate is the observable
 *
 * A conversion's status is one bit; the number that decides whether a customer was treated
 * correctly is the rate applied and the amount received. Both are read back with a separate `GET`,
 * against the rate independently quoted by `/api/v1/fx/rates/{base}/{quote}` before the trade.
 */
@QuarkusTest
@QuarkusTestResource(FxConversionJourneyE2E.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.fx.it.PostgresRedisTestResource::class)
class FxConversionJourneyE2E {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = InMemoryConnector.switchOutgoingChannelsToInMemory("fx-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @BeforeEach
    fun screeningIsClear() {
        val port = mockk<SanctionsScreeningAdapter>()
        coEvery { port.screen(any(), any(), any()) } answers {
            ScreeningResult(
                subject = firstArg(),
                role = ScreeningRole.DEBTOR,
                status = ScreeningMatchStatus.CLEAR,
                score = 0.0,
                matchedEntity = null,
            )
        }
        QuarkusMock.installMockForType(port, SanctionsScreeningAdapter::class.java)
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_PAYMENTS"])
    fun `a quoted rate is the rate a settled conversion is read back at`() {
        // 1. The customer asks what the bank quotes.
        val quote = RestAssured.given().get("/api/v1/fx/rates/CZK/EUR")
        assertThat(quote.statusCode)
            .describedAs("GET /api/v1/fx/rates/CZK/EUR: %s", quote.body.asString())
            .isEqualTo(200)
        // `askRate` is the side a customer BUYS the quote currency at, and it is what
        // `FxService` stamps onto the conversion as `appliedRate`.
        val quotedAskRate = BigDecimal(quote.jsonPath().getString("askRate"))

        // 2. They convert 1000.00 CZK.
        val executed = convert(idempotencyKey = "e2e-journey-${UUID.randomUUID()}")
        assertThat(executed.statusCode)
            .describedAs("POST /api/v1/fx/convert: %s", executed.body.asString())
            .isEqualTo(201)
        assertThat(executed.jsonPath().getString("status")).isEqualTo("SETTLED")
        val conversionId = executed.jsonPath().getString("id")

        // 3. Read the conversion back — a separate query path from the one that wrote it.
        val readBack = RestAssured.given().get("/api/v1/fx/conversions/$conversionId")
        assertThat(readBack.statusCode)
            .describedAs("GET /api/v1/fx/conversions/%s: %s", conversionId, readBack.body.asString())
            .isEqualTo(200)
        val stored = readBack.jsonPath()
        assertThat(stored.getString("status")).isEqualTo("SETTLED")
        assertThat(stored.getString("fromCurrency")).isEqualTo("CZK")
        assertThat(stored.getString("toCurrency")).isEqualTo("EUR")
        assertThat(stored.getLong("fromAmountMinorUnits")).isEqualTo(FROM_MINOR_UNITS)

        // 4. The customer actually received something, and the persisted amounts agree with what
        //    the write returned. A conversion that settles at zero is the silent-loss defect.
        assertThat(stored.getLong("toAmountMinorUnits"))
            .describedAs("a SETTLED conversion must credit a non-zero amount")
            .isGreaterThan(0L)
            .isEqualTo(executed.jsonPath().getLong("toAmountMinorUnits"))
        assertThat(BigDecimal(stored.getString("appliedRate")))
            .describedAs("the rate on the stored conversion must be the ask rate quoted before the trade")
            .isEqualByComparingTo(quotedAskRate)
        assertThat(stored.getString("settledAt"))
            .describedAs("a SETTLED conversion must carry a settlement time")
            .isNotNull()
    }

    /**
     * The retry a client makes when it does not know whether its first call landed: the same
     * `Idempotency-Key` must return the SAME conversion, not a second trade at a second rate.
     * Asserted on the id read back, not on the status code.
     */
    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_PAYMENTS"])
    fun `a retried conversion replays the first trade instead of executing a second`() {
        val key = "e2e-journey-replay-${UUID.randomUUID()}"

        val first = convert(key)
        assertThat(first.statusCode).isEqualTo(201)
        val firstId = first.jsonPath().getString("id")

        val retry = convert(key)
        assertThat(retry.statusCode).isEqualTo(201)
        assertThat(retry.jsonPath().getString("id"))
            .describedAs("a retry under the same Idempotency-Key must not execute a second trade")
            .isEqualTo(firstId)

        // A genuinely new key IS a new trade — the control that proves the check above can fail.
        val fresh = convert("e2e-journey-replay-${UUID.randomUUID()}")
        assertThat(fresh.statusCode).isEqualTo(201)
        assertThat(fresh.jsonPath().getString("id")).isNotEqualTo(firstId)
    }

    /** Known-negative control: the read-back observable must be able to say "no such conversion". */
    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_PAYMENTS"])
    fun `the conversion observable answers 404 for a conversion that was never executed`() {
        assertThat(
            RestAssured.given().get("/api/v1/fx/conversions/${UUID.randomUUID()}").statusCode,
        ).isEqualTo(404)
    }

    private fun convert(idempotencyKey: String): Response = RestAssured.given()
        .contentType("application/json")
        .header("Idempotency-Key", idempotencyKey)
        .body(
            """
            {
              "partyId": "$PARTY_ID",
              "accountId": null,
              "partyName": "Jan Novak",
              "fromCurrency": "CZK",
              "toCurrency": "EUR",
              "fromAmountMinorUnits": $FROM_MINOR_UNITS
            }
            """.trimIndent(),
        )
        .post("/api/v1/fx/convert")

    private companion object {
        const val ACTOR = "00000000-0000-0000-0000-000000000099"
        const val PARTY_ID = "00000000-3333-0000-0000-000000000001"
        const val FROM_MINOR_UNITS = 100_000L
    }
}
