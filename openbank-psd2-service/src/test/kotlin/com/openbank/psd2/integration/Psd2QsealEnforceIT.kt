// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.integration

import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

/**
 * With `openbank.psd2.qseal.enforce=true` the QSEAL filter must reject an unsigned or forged Berlin
 * write on REAL HTTP — i.e. the body is buffered before the filter (no IO-thread blocking read)
 * and the filter's rejection reaches the client. Also pins the order: the eIDAS gate
 * (AUTHENTICATION) answers before the QSEAL gate (AUTHORIZATION).
 */
@QuarkusTest
@TestProfile(Psd2QsealEnforceIT.QsealEnforceProfile::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_psd2_it")],
)
class Psd2QsealEnforceIT {

    private val paymentPath = "/v1/payments/sepa-credit-transfers"
    private val body = """{"instructedAmount":{"currency":"EUR","amount":"1.00"}}"""

    @Test
    fun `an unsigned payment initiation is rejected with SIGNATURE_INVALID`() {
        Given {
            header("X-TPP-ID", FakeTppRegistryRestClient.AUTHORIZED_TPP)
            contentType("application/json")
            body(body)
        } When { post(paymentPath) } Then {
            statusCode(401)
            body("tppMessages[0].code", equalTo("SIGNATURE_INVALID"))
        }
    }

    @Test
    fun `a digest that does not match the wire bytes is rejected`() {
        Given {
            header("X-TPP-ID", FakeTppRegistryRestClient.AUTHORIZED_TPP)
            header("Digest", "SHA-256=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
            header("Signature", "keyId=\"tpp\",algorithm=\"rsa-sha256\",headers=\"digest\",signature=\"AAAA\"")
            header("TPP-Signature-Certificate", "not-a-certificate")
            contentType("application/json")
            body(body)
        } When { post("/v1/consents") } Then {
            statusCode(401)
            body("tppMessages[0].code", equalTo("SIGNATURE_INVALID"))
        }
    }

    @Test
    fun `the eIDAS gate answers before the QSEAL gate`() {
        Given {
            contentType("application/json")
            body(body)
        } When { post(paymentPath) } Then {
            statusCode(401)
            body("tppMessages[0].code", equalTo("CERTIFICATE_MISSING"))
        }
    }

    class QsealEnforceProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> =
            mapOf("authz.enforce" to "false", "openbank.psd2.qseal.enforce" to "true")
    }
}
