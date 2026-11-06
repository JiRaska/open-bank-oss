// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.integration

import com.openbank.libs.testing.containers.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The nightly API fuzz (run 41631915107) sent `/documents/upload` a multipart body whose closing
 * boundary is not CRLF-delimited, so the parser runs off the end of the data. That was a 500; the
 * body is the caller's framing error, so it is a 400. Driven over real HTTP as the fuzzer drove it.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedpandaTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_party_multipart_it")],
)
class PartyTruncatedMultipartIT {

    @Test
    @TestSecurity(user = "fuzz-user", roles = ["ROLE_CUSTOMER"])
    fun `a truncated multipart upload is a 400, not a 500`() {
        val boundary = "09dcbc4d91cf756b4c099b9e896e07d2"
        Given {
            contentType("multipart/form-data; boundary=$boundary")
            body("--$boundary\r\n[{'x': False}, [[], 0.85]]--$boundary--\r\n".toByteArray())
        } When {
            post("/api/v1/parties/${UUID.randomUUID()}/documents/upload")
        } Then {
            statusCode(HTTP_BAD_REQUEST)
        }
    }

    private companion object {
        const val HTTP_BAD_REQUEST = 400
    }
}
