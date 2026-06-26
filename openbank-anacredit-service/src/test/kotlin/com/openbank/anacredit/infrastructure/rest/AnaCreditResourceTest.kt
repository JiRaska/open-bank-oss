// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.anacredit.infrastructure.rest

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

@QuarkusTest
class AnaCreditResourceTest {

    private fun register(payload: String) {
        Given { contentType("application/json"); body(payload) } When
            { post("/api/v1/anacredit/exposures") } Then { statusCode(201) }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_ADMIN"])
    fun `a rendered return reports the legal-entity overdraft and excludes the consumer one`() {
        register(
            """{"instrumentId":"OD-RT-1","debtorId":"LE-RT-ACME","debtorType":"LEGAL_ENTITY",
               "currency":"EUR","committedAmount":40000,"drawnAmount":12000,
               "committedAmountEur":40000,"originationDate":"2025-06-01"}""",
        )
        register(
            """{"instrumentId":"OD-RT-2","debtorId":"NP-RT-JOE","debtorType":"NATURAL_PERSON",
               "currency":"EUR","committedAmount":3000,"drawnAmount":1000,
               "committedAmountEur":3000,"originationDate":"2025-06-01"}""",
        )

        val body = (Given { this } When { get("/api/v1/anacredit/returns/2026-01-31") } Then { statusCode(200) })
            .extract().body().asString()

        assertThat(body).contains("OD-RT-1")
        assertThat(body).contains("HOUSEHOLD_OUT_OF_SCOPE")
        assertThat(body).contains("\"defaultStatus\":\"NOT_IN_DEFAULT\"")
    }
}
