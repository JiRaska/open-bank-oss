// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.integration

import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test

/**
 * The eIDAS/TPP gate and the RFC 8594 deprecation headers must actually fire on a REAL request
 * (#10997). Both filters matched `uriInfo.path` against prefixes without a leading slash, while
 * RESTEasy Reactive hands them `/v1/...`, so neither ever ran: every TPP-identified call fell
 * through to the resource's own fail-closed `CERTIFICATE_MISSING`, and no bespoke response carried
 * `Deprecation`/`Sunset`. A unit test that builds its own `UriInfo` chooses the path form itself
 * and cannot see which one the runtime supplies — only real HTTP can.
 *
 * Discriminators: the filter's rejection carries the text "eIDAS QWAC certificate or X-TPP-ID
 * header required" (the resource's carries none), a rejected TPP gets the filter-only
 * `CERTIFICATE_INVALID`, and an authorised TPP gets PAST the filter to the use case instead of the
 * resource's 401.
 */
@QuarkusTest
@TestProfile(Psd2MissingHeaderStatusIT.AuthzOffProfile::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_psd2_it")],
)
class Psd2FilterPathGatingIT {

    private val consentPath = "/v1/consents/00000000-0000-0000-0000-000000000000/status"

    @Test
    fun `the eIDAS filter rejects a v1 call with no TPP identification`() {
        Given { this } When { get(consentPath) } Then {
            statusCode(401)
            body("tppMessages[0].code", equalTo("CERTIFICATE_MISSING"))
            body("tppMessages[0].text", equalTo("eIDAS QWAC certificate or X-TPP-ID header required"))
        }
    }

    @Test
    fun `both domestic PIS surfaces reject missing TPP identification before parsing a payment`() {
        listOf("/v1/payments/domestic-cz", "/open-banking/v2/payments/domestic-cz").forEach { path ->
            Given { contentType("application/json").body("{}") } When { post(path) } Then {
                statusCode(401)
                body("tppMessages[0].code", equalTo("CERTIFICATE_MISSING"))
            }
        }
    }

    @Test
    fun `the eIDAS filter rejects a TPP the registry does not authorise`() {
        Given { header("X-TPP-ID", "TPP-IT-UNKNOWN") } When { get(consentPath) } Then {
            statusCode(401)
            body("tppMessages[0].code", equalTo("CERTIFICATE_INVALID"))
        }
    }

    @Test
    fun `an authorised TPP passes the filter and reaches the use case`() {
        Given { header("X-TPP-ID", FakeTppRegistryRestClient.AUTHORIZED_TPP) } When { get(consentPath) } Then {
            statusCode(not(equalTo(401)))
        }
    }

    @Test
    fun `a bespoke open-banking response carries Deprecation and Sunset`() {
        Given { this } When { get("/open-banking/v2/accounts") } Then {
            header("Deprecation", "true")
            header("Sunset", "Wed, 31 Dec 2031 23:59:59 GMT")
        }
    }
}
