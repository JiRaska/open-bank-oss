// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * A granted account authorization must be revocable. `AccountAuthorization` has an application-assigned
 * @Id, so `AccountAuthorizationRepositoryImpl.save` mapping the domain to a fresh entity and
 * `persist`ing it schedules an INSERT for every write — grant works, but revoke/suspend/reinstate
 * (which re-save the same id) fail on the primary key. Invisible to `AuthorizationServiceTest` (mocks
 * the repository); only a real DB shows it.
 *
 * Drives create-account -> grant -> revoke through the real REST endpoints (a bare-thread reactive
 * repository call throws "No current Vertx context found"; only an HTTP request carries one) and
 * asserts the row transitioned via a plain JDBC read.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.account.it.PostgresRedpandaRedisTestResource::class)
class AccountAuthorizationLifecycleIT {

    @Inject
    lateinit var dataSource: DataSource

    private val operator = "00000000-0000-0000-0000-0000000000aa"

    private fun openAccount(): UUID {
        val payload = """
            {"partyId":"${UUID.randomUUID()}","productId":"${UUID.randomUUID()}",
            "accountType":"CURRENT","currencyCode":"CZK","legalName":"Auth IT Customer"}
        """.trimIndent()
        val id = Given {
            contentType("application/json")
            header("Idempotency-Key", UUID.randomUUID().toString())
            body(payload)
        } When {
            post("/api/v1/accounts")
        } Then {
            statusCode(201)
        } Extract {
            jsonPath().getString("id")
        }
        return UUID.fromString(id)
    }

    private fun grant(accountId: UUID): UUID {
        val payload = """
            {"partyId":"${UUID.randomUUID()}","role":"FULL_ACCESS","dailyLimit":null,"transactionLimit":null,
            "validFrom":"${LocalDate.now()}","validTo":null,"grantedBy":"$operator"}
        """.trimIndent()
        val response = Given {
            contentType("application/json")
            body(payload)
        } When {
            post("/api/v1/accounts/$accountId/authorizations")
        } Then {
            statusCode(201)
        } Extract {
            this
        }
        assertThat(response.jsonPath().getString("status")).isEqualTo("ACTIVE")
        return UUID.fromString(response.jsonPath().getString("id"))
    }

    private fun authStatus(id: UUID): String? = dataSource.connection.use { conn ->
        val ps = conn.prepareStatement("SELECT status FROM account_authorizations WHERE id = ?")
        ps.setObject(1, id)
        val rs = ps.executeQuery()
        if (rs.next()) rs.getString("status") else null
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-0000000000aa", roles = ["ROLE_OPERATOR"])
    fun `a granted authorization can be revoked and the row transitions to REVOKED`() {
        val accountId = openAccount()
        val authId = grant(accountId)

        Given {
            contentType("application/json")
            body("""{"revokedBy":"$operator","reason":"customer request"}""")
        } When {
            delete("/api/v1/accounts/$accountId/authorizations/$authId")
        } Then {
            statusCode(200)
            body("status", org.hamcrest.Matchers.equalTo("REVOKED"))
        }

        assertThat(authStatus(authId)).describedAs("account_authorizations.status after revoke").isEqualTo("REVOKED")
    }

    /**
     * #5913. `AuthorizationNotFoundException` had no mapper, so it fell through to libs-runtime's
     * `GenericExceptionMapper` and a miss answered **500 INTERNAL_ERROR** — measured on the
     * authenticated-fuzz lane, run 33720692606, traceId 0466cbbc-17c8-4dee-b40a-94c5bfdcd5d9.
     *
     * `AuthorizationServiceTest` already asserts the exception TYPE against a mocked repository
     * and passes against the broken code: the type was never in doubt, the STATUS was. Only a
     * request through the real endpoint can tell a 404 from a 500, which is why this lives here.
     */
    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-0000000000aa", roles = ["ROLE_OPERATOR"])
    fun `revoking an authorization that does not exist answers 404, not 500`() {
        val accountId = openAccount()

        Given {
            contentType("application/json")
            body("""{"revokedBy":"$operator","reason":"customer request"}""")
        } When {
            delete("/api/v1/accounts/$accountId/authorizations/${UUID.randomUUID()}")
        } Then {
            statusCode(404)
            body("code", org.hamcrest.Matchers.equalTo("AUTHORIZATION_NOT_FOUND"))
        }
    }

    /**
     * The sibling case: the id resolves to a real row, on somebody else's account. It must answer
     * 404 with the SAME body as the unknown-id case above — a different status or message here
     * would make the endpoint an existence oracle for authorization ids.
     */
    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-0000000000aa", roles = ["ROLE_OPERATOR"])
    fun `revoking an authorization that belongs to another account answers 404`() {
        val ownerAccount = openAccount()
        val otherAccount = openAccount()
        val authId = grant(ownerAccount)

        Given {
            contentType("application/json")
            body("""{"revokedBy":"$operator","reason":"customer request"}""")
        } When {
            delete("/api/v1/accounts/$otherAccount/authorizations/$authId")
        } Then {
            statusCode(404)
            body("code", org.hamcrest.Matchers.equalTo("AUTHORIZATION_NOT_FOUND"))
        }

        assertThat(authStatus(authId))
            .describedAs("a refused cross-account revoke must not have transitioned the row")
            .isEqualTo("ACTIVE")
    }
}
