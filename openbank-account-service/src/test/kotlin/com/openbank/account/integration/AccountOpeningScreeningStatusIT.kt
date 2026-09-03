// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.integration

import com.openbank.account.application.port.out.AccountScreeningUnavailableException
import com.openbank.account.application.port.out.SanctionsScreenResult
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The two outcomes of the ADR-0032 §C sanctions gate, driven through the real endpoint.
 *
 * `AccountServiceTest` already covers both, and cannot see this defect: it asserts the exception
 * TYPE against a mocked port, and the type was never wrong — the STATUS was. Both exceptions had no
 * `ExceptionMapper`, so both fell through to libs-runtime's `GenericExceptionMapper` and told the
 * caller **500 INTERNAL_ERROR**: an availability failure and a policy refusal, reported as "the
 * server broke". Only a request through the JAX-RS boundary reaches a mapper at all.
 *
 * The third assertion here is not about a status code. `AccountOpeningBlockedByScreeningException`'s
 * message embeds `matched: <name>` — the sanctions-list name the screen hit. Re-parenting it to
 * `IllegalStateException` (the obvious one-word fix, and what its sibling `ProductNotEligibleException`
 * does) would have resolved it to libs-runtime's `IllegalStateExceptionMapper`, which echoes
 * `exception.message` verbatim into the response body. That turns a 422 into a sanctions-screening
 * disclosure, so the mapper is service-local and answers a fixed message instead.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.account.it.PostgresRedpandaRedisTestResource::class)
class AccountOpeningScreeningStatusIT {

    @Inject
    lateinit var screening: TestSanctionsScreeningPort

    /** The stub is application-scoped and shared by the whole IT suite — never leave it armed. */
    @AfterEach
    fun restoreClearScreening() = screening.reset()

    private fun postOpenAccount(legalName: String) = Given {
        contentType("application/json")
        header("Idempotency-Key", UUID.randomUUID().toString())
        body(
            """
            {"partyId":"${UUID.randomUUID()}","productId":"${UUID.randomUUID()}",
            "accountType":"CURRENT","currencyCode":"CZK","legalName":"$legalName"}
            """.trimIndent(),
        )
    } When {
        post("/api/v1/accounts")
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-0000000000aa", roles = ["ROLE_OPERATOR"])
    fun `a sanctions HIT refuses the open with 422, not 500`() {
        screening.behaviour = { _, _ -> SanctionsScreenResult("HIT", 0.98, MATCHED_ALIAS) }

        postOpenAccount("Sanctioned Person") Then {
            statusCode(422)
            body("code", equalTo("ACCOUNT_OPENING_BLOCKED"))
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-0000000000aa", roles = ["ROLE_OPERATOR"])
    fun `a sanctions REVIEW refuses the open with 422, not 500`() {
        screening.behaviour = { _, _ -> SanctionsScreenResult("REVIEW", 0.72, MATCHED_ALIAS) }

        postOpenAccount("Fuzzy Match Person") Then {
            statusCode(422)
            body("code", equalTo("ACCOUNT_OPENING_BLOCKED"))
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-0000000000aa", roles = ["ROLE_OPERATOR"])
    fun `the refusal body never carries the sanctions matched name`() {
        screening.behaviour = { _, _ -> SanctionsScreenResult("HIT", 0.98, MATCHED_ALIAS) }

        val body = postOpenAccount("Sanctioned Person") Then {
            statusCode(422)
        } Extract {
            body().asString()
        }

        assertThat(body).doesNotContain(MATCHED_ALIAS)
        assertThat(body).doesNotContain("matched")
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-0000000000aa", roles = ["ROLE_OPERATOR"])
    fun `an unreachable sanctions service answers 503 with Retry-After, not 500`() {
        screening.behaviour = { _, _ ->
            throw AccountScreeningUnavailableException(RuntimeException("connect timed out"))
        }

        postOpenAccount("Test Customer") Then {
            statusCode(503)
            body("code", equalTo("SCREENING_UNAVAILABLE"))
            header("Retry-After", notNullValue())
        }
    }

    companion object {
        /** Distinctive so the disclosure assertion cannot pass by accident. */
        private const val MATCHED_ALIAS = "MATCHED SANCTIONS ALIAS"
    }
}
