// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.customeredge.integration

import com.openbank.customeredge.contract.StubUpstreamResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private const val ME = "5a1b2c3d-4e5f-4a6b-8c7d-0e1f2a3b4c5d"
private const val OWNER = "6b2c3d4e-5f60-4b7c-9d8e-1f2a3b4c5d6e"
private const val OWN_ACCOUNT = "11111111-1111-4111-8111-111111111111"
private const val SHARED = "22222222-2222-4222-8222-222222222222"
private const val OTHER = "33333333-3333-4333-8333-333333333333"

/**
 * `GET /customer/v1/accounts` over real HTTP (#13711). account-service answers a page object
 * `{data, pagination}`; delegated accounts must land in `data`. The negative cases (no grant,
 * REVOKED, no read capability) are what make the positive one mean something.
 */
@QuarkusTest
@QuarkusTestResource(StubUpstreamResource::class, restrictToAnnotatedClass = true)
@TestSecurity(user = "customer:$ME", roles = ["ROLE_CUSTOMER"])
@OidcSecurity(claims = [Claim(key = "party_id", value = ME)])
class DelegatedAccountsListIT {

    @BeforeEach
    fun seed() {
        StubUpstreamResource.reset()
        StubUpstreamResource.stub(
            "/api/v1/accounts",
            body = """{"data":[{"id":"$OWN_ACCOUNT","partyId":"$ME"}],""" +
                """"pagination":{"limit":50,"hasNextPage":true,"nextCursor":"abc","totalCount":7}}""",
        )
        StubUpstreamResource.stub("/api/v1/accounts/$SHARED", body = """{"id":"$SHARED","partyId":"$OWNER"}""")
        StubUpstreamResource.stub("/api/v1/accounts/$OTHER", body = """{"id":"$OTHER","partyId":"$OWNER"}""")
    }

    private fun grants(vararg rows: String) =
        StubUpstreamResource.stub("/api/v1/delegations/grantee/$ME", body = "[${rows.joinToString(",")}]")

    private fun grant(id: String, status: String = "ACTIVE", caps: String = "\"ACCOUNT_READ_BALANCES\"") =
        """{"status":"$status","resourceType":"ACCOUNT","resourceId":"$id","capabilities":[$caps]}"""

    @Test
    fun `a delegated account appears in data, marked, with totalCount raised and cursors untouched`() {
        grants(grant(SHARED))
        When { get("/customer/v1/accounts") } Then {
            statusCode(200)
            body("data.id", contains(OWN_ACCOUNT, SHARED))
            body("data[1].sharedWithMe", equalTo(true))
            body("pagination.totalCount", equalTo(8))
            body("pagination.nextCursor", equalTo("abc"))
            body("pagination.hasNextPage", equalTo(true))
        }
    }

    @Test
    fun `without a delegation only the caller's own account is listed`() {
        grants()
        When { get("/customer/v1/accounts") } Then {
            statusCode(200)
            body("data.id", contains(OWN_ACCOUNT))
            body("pagination.totalCount", equalTo(7))
        }
    }

    @Test
    fun `a REVOKED grant does not list the account`() {
        grants(grant(SHARED, status = "REVOKED"))
        When { get("/customer/v1/accounts") } Then {
            body("data.id", not(hasItem(SHARED)))
            body("pagination.totalCount", equalTo(7))
        }
    }

    @Test
    fun `a grant without read capability does not list the account`() {
        grants(grant(SHARED, caps = "\"ACCOUNT_INITIATE_PAYMENT\""))
        When { get("/customer/v1/accounts") } Then {
            body("data.id", not(hasItem(SHARED)))
            body("pagination.totalCount", equalTo(7))
        }
    }

    @Test
    fun `a cursor page carries no shared accounts and an unchanged totalCount`() {
        grants(grant(SHARED))
        Given { queryParam("cursor", "abc") } When { get("/customer/v1/accounts") } Then {
            body("data.id", contains(OWN_ACCOUNT))
            body("pagination.totalCount", equalTo(7))
        }
    }

    @Test
    fun `a fetched body whose id differs from the grant is not listed`() {
        grants(grant(OTHER))
        StubUpstreamResource.stub("/api/v1/accounts/$OTHER", body = """{"id":"$SHARED","partyId":"$OWNER"}""")
        When { get("/customer/v1/accounts") } Then {
            body("data.id", contains(OWN_ACCOUNT))
            body("pagination.totalCount", equalTo(7))
        }
    }

    @Test
    fun `only the readable grant among several is listed`() {
        grants(grant(SHARED), grant(OTHER, status = "REVOKED"))
        When { get("/customer/v1/accounts") } Then {
            body("data.id", contains(OWN_ACCOUNT, SHARED))
        }
    }
}
