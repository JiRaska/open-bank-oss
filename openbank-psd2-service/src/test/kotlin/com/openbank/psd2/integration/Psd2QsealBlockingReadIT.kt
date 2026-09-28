// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.integration

import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.http.ContentType
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test

/**
 * Regression for #11008-follow-up (issue TBD): once #10997 made `EidasMtlsFilter` and
 * `QsealSignatureFilter` actually run on `/v1` traffic (path normalisation), a **POST** to the
 * Berlin write surface started reading `ContainerRequestContext.entityStream` — a genuinely
 * BLOCKING call — from [QsealSignatureFilter], on the Vert.x IO thread. `BerlinConsentResource
 * .createConsent` is a Kotlin `suspend fun`, which RESTEasy Reactive treats as non-blocking, and a
 * request filter inherits the thread of the resource method it guards, so the filter never gets a
 * worker thread of its own: the read throws `BlockingOperationNotAllowedException` ("Attempting a
 * blocking read on io thread") before any resource code runs, and the client sees a bare 500.
 *
 * This can only be seen over REAL HTTP: a unit test on the filter class builds its own
 * `ContainerRequestContext` mock, whose `entityStream.readBytes()` is plain in-memory Kotlin and
 * never touches Vert.x's IO-thread guard — it is green against the broken filter for exactly the
 * same reason the mockk-based `QsealSignatureFilterTest` never caught this.
 *
 * The discriminator is deliberately NOT "the response is 2xx" — `createConsent`'s downstream
 * `ConsentServiceClient` has no fake in this module's IT suite, so a real call to it fails for
 * unrelated reasons. Instead this omits the mandatory `X-Request-ID` header, which
 * `createConsent` rejects with a deterministic 400 **before** it ever calls the downstream
 * client — reaching that check at all proves both filters ran to completion. Before the fix the
 * request never gets that far: it 500s inside `QsealSignatureFilter`, so the response is neither
 * 400 nor the exact body `createConsent` returns.
 */
@QuarkusTest
@TestProfile(Psd2MissingHeaderStatusIT.AuthzOffProfile::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_psd2_it")],
)
class Psd2QsealBlockingReadIT {

    // Valid ObConsentRequest JSON so the body deserialises cleanly and the request reaches
    // createConsent's own header validation instead of failing on a malformed payload.
    private val validConsentBody = """
        {
          "access": {"accounts": null, "balances": null, "transactions": null, "additionalInformation": null},
          "recurringIndicator": false,
          "validUntil": "2027-01-01",
          "frequencyPerDay": 4
        }
    """.trimIndent()

    @Test
    fun `a POST to the Berlin write surface reaches the resource instead of crashing in the QSEAL filter`() {
        Given {
            header("X-TPP-ID", FakeTppRegistryRestClient.AUTHORIZED_TPP)
            contentType(ContentType.JSON)
            body(validConsentBody)
            // X-Request-ID deliberately omitted: createConsent's own 400 for its absence is the
            // discriminator — reaching it proves EidasMtlsFilter + QsealSignatureFilter both
            // completed without blocking the IO thread.
        } When {
            post("/v1/consents")
        } Then {
            // Fails on main with a bare 500 (BlockingOperationNotAllowedException from the QSEAL
            // filter's entityStream.readBytes()) before the resource's own validation ever runs.
            statusCode(not(500))
            statusCode(400)
        }
    }
}
