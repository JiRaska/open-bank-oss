// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.integration

import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A real Berlin `/v1` POST must get THROUGH the eIDAS and QSEAL filters to the `suspend` resource.
 *
 * Once #10997 made the filters run, both did blocking work on the Vert.x IO thread ahead of the
 * Kotlin `suspend` resources — `EidasMtlsFilter`'s synchronous tpp-registry call and
 * `QsealSignatureFilter`'s `entityStream.readBytes()` — and every signed-surface POST answered 422
 * "Attempting a blocking read on io thread". The GET-only [Psd2FilterPathGatingIT] carries no body
 * and could not see it. Discriminator: the response is neither the filter's 401 nor the IO-thread
 * 422, and never mentions a blocking read.
 */
@QuarkusTest
@TestProfile(Psd2MissingHeaderStatusIT.AuthzOffProfile::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_psd2_it")],
)
class Psd2BerlinWriteFilterThreadIT {

    private fun post(path: String, body: String): Pair<Int, String> {
        val response = Given {
            header("X-TPP-ID", FakeTppRegistryRestClient.AUTHORIZED_TPP)
            header("X-Request-ID", "11111111-1111-1111-1111-111111111111")
            header("Consent-ID", "00000000-0000-0000-0000-000000000000")
            contentType("application/json")
            body(body)
        } When { post(path) } Then { } Extract { response() }
        return response.statusCode to response.body.asString()
    }

    private fun assertReachedResource(result: Pair<Int, String>) {
        val (status, body) = result
        assertThat(body).doesNotContainIgnoringCase("blocking")
        assertThat(status).`as`(body).isNotEqualTo(422)
        // The filters' own rejections; a 401 the resource or use case raises (e.g. unknown consent) is fine.
        assertThat(body).doesNotContain("CERTIFICATE_MISSING", "CERTIFICATE_INVALID", "SIGNATURE_INVALID")
    }

    @Test
    fun `a Berlin payment initiation POST passes both filters`() {
        assertReachedResource(
            post(
                "/v1/payments/sepa-credit-transfers",
                """{"instructedAmount":{"currency":"EUR","amount":"1.00"},""" +
                    """"debtorAccount":{"iban":"CZ6508000000192000145399"},""" +
                    """"creditorAccount":{"iban":"CZ6508000000192000145399"},"creditorName":"IT"}""",
            ),
        )
    }

    @Test
    fun `a Berlin consent creation POST passes both filters`() {
        assertReachedResource(
            post(
                "/v1/consents",
                """{"access":{"accounts":[{"iban":"CZ6508000000192000145399"}]},""" +
                    """"recurringIndicator":false,"validUntil":"2099-12-31","frequencyPerDay":4}""",
            ),
        )
    }
}
