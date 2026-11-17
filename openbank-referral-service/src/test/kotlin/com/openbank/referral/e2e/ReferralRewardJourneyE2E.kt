// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.referral.e2e

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID

/**
 * The referral reward journey over the real HTTP surface: an operator drafts a referral program,
 * an independent checker publishes it, a referrer issues an idempotency-keyed invite, the referee
 * is attributed, and the qualifying event requests a reward that replays idempotently — while
 * the endpoints prove the boundaries the service enforces: the maker cannot publish their own
 * draft, unpublished programs are invisible, a reused idempotency key is refused, unknown tokens
 * 404, and an event that does not qualify the program is a conflict.
 *
 * The module's tests prove one property each; this proves the route sequence serves the whole
 * "program is approved, invite converts, reward is requested" story, so a wiring regression that
 * deregisters a route (#3371), drops the maker/checker binding at publish, or accepts any event
 * as qualification fails here even when every property-level assertion stays green.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestMethodOrder(OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestSecurity(user = MAKER, roles = ["ROLE_OPERATOR"])
class ReferralRewardJourneyE2E {

    private val programName = "e2e-referral-${UUID.randomUUID()}"
    private lateinit var programId: String
    private lateinit var draftOnlyId: String
    private lateinit var inviteToken: String
    private lateinit var rewardReference: String

    @Test
    @Order(1)
    fun `1 - an operator files a referral program draft`() {
        draftOnlyId = createProgram("e2e-draft-only-${UUID.randomUUID()}")

        val created = Given {
            contentType("application/json")
            body(programBody(programName))
        } When {
            post("/api/v1/referrals/programs")
        } Then {
            statusCode(201)
            body("name", equalTo(programName))
            body("status", equalTo("DRAFT"))
            body("rewardAmount", equalTo(10))
            body("currency", equalTo("EUR"))
        } Extract { path<String>("id") }
        programId = created
    }

    @Test
    @Order(2)
    fun `2 - the maker cannot publish their own draft`() {
        Given { contentType("application/json") }
            .When { post("/api/v1/referrals/programs/$draftOnlyId/publish") }
            .Then { statusCode(409) }
    }

    @Test
    @Order(3)
    @TestSecurity(user = CHECKER, roles = ["ROLE_OPERATOR"])
    fun `3 - an independent checker publishes the program`() {
        Given { contentType("application/json") }
            .When { post("/api/v1/referrals/programs/$programId/publish") }
            .Then {
                statusCode(200)
                body("status", equalTo("PUBLISHED"))
                body("checker", equalTo(CHECKER))
            }
    }

    @Test
    @Order(4)
    fun `4 - the published program is served and the unpublished draft is not`() {
        Given { contentType("application/json") }
            .When { get("/api/v1/referrals/programs/$programId") }
            .Then {
                statusCode(200)
                body("id", equalTo(programId))
                body("status", equalTo("PUBLISHED"))
            }

        Given { contentType("application/json") }
            .When { get("/api/v1/referrals/programs/$draftOnlyId") }
            .Then { statusCode(404) }
    }

    @Test
    @Order(5)
    fun `5 - the referrer issues an invite over an idempotency key`() {
        val tokenAndLink = Given {
            contentType("application/json")
            header("Idempotency-Key", "e2e-invite-$programId")
            body("""{"referrerPartyId":"${UUID.randomUUID()}"}""")
        } When {
            post("/api/v1/referrals/programs/$programId/invites")
        } Then {
            statusCode(201)
            body("status", equalTo("ISSUED"))
            body("token", notNullValue())
            body("referralLink", notNullValue())
        } Extract { path<String>("token") to path<String>("referralLink") }
        inviteToken = tokenAndLink.first

        // The link is the configured base with exactly one '/' before the raw token.
        assertThat(tokenAndLink.second).isEqualTo("https://app.open-bank.tech/r/${tokenAndLink.first}")
    }

    @Test
    @Order(6)
    fun `6 - replaying the invite with the same idempotency key is refused`() {
        Given {
            contentType("application/json")
            header("Idempotency-Key", "e2e-invite-$programId")
            body("""{"referrerPartyId":"${UUID.randomUUID()}"}""")
        } When {
            post("/api/v1/referrals/programs/$programId/invites")
        } Then {
            statusCode(409)
            body("reason", equalTo("IDEMPOTENCY_KEY_REUSED"))
        }
    }

    @Test
    @Order(7)
    fun `7 - attributing a referee works and unknown tokens do not`() {
        Given {
            contentType("application/json")
            header("Idempotency-Key", "e2e-attribute-$programId")
            body("""{"refereePartyId":"${UUID.randomUUID()}"}""")
        } When {
            post("/api/v1/referrals/invites/$inviteToken/attribute")
        } Then {
            statusCode(200)
            body("status", equalTo("ATTRIBUTED"))
        }

        Given {
            contentType("application/json")
            header("Idempotency-Key", "e2e-attribute-unknown-$programId")
            body("""{"refereePartyId":"${UUID.randomUUID()}"}""")
        } When {
            post("/api/v1/referrals/invites/${UUID.randomUUID()}/attribute")
        } Then {
            statusCode(404)
        }
    }

    @Test
    @Order(8)
    fun `8 - qualification requires the program's qualifying event`() {
        Given {
            contentType("application/json")
            header("Idempotency-Key", "e2e-qualify-wrong-$programId")
            body("""{"eventName":"card.issued","eventId":"e2e-wrong-$programId"}""")
        } When {
            post("/api/v1/referrals/invites/$inviteToken/qualify")
        } Then {
            statusCode(409)
        }

        val reference = Given {
            contentType("application/json")
            header("Idempotency-Key", "e2e-qualify-$programId")
            body("""{"eventName":"account.opened","eventId":"e2e-event-$programId"}""")
        } When {
            post("/api/v1/referrals/invites/$inviteToken/qualify")
        } Then {
            statusCode(202)
            body("status", equalTo("REWARD_REQUESTED"))
            body("rewardReference", notNullValue())
        } Extract { path<String>("rewardReference") }
        rewardReference = reference
    }

    @Test
    @Order(9)
    fun `9 - a repeated qualification replays the same reward reference`() {
        Given {
            contentType("application/json")
            header("Idempotency-Key", "e2e-qualify-replay-$programId")
            body("""{"eventName":"account.opened","eventId":"e2e-event-$programId"}""")
        } When {
            post("/api/v1/referrals/invites/$inviteToken/qualify")
        } Then {
            statusCode(202)
            body("rewardReference", equalTo(rewardReference))
        }
    }

    @Test
    @Order(10)
    @TestSecurity(user = "viewer@openbank.test", roles = ["ROLE_VIEWER"])
    fun `10 - a viewer cannot operate the referral API`() {
        Given {
            contentType("application/json")
            body(programBody("e2e-viewer-${UUID.randomUUID()}"))
        } When {
            post("/api/v1/referrals/programs")
        } Then {
            statusCode(403)
        }
    }

    private fun createProgram(name: String): String = Given {
        contentType("application/json")
        body(programBody(name))
    } When {
        post("/api/v1/referrals/programs")
    } Then {
        statusCode(201)
    } Extract { path<String>("id") }

    private fun programBody(name: String) = """
        {"name":"$name","version":1,"rewardAmount":10,"currency":"EUR","qualifyingEvent":"account.opened"}
    """.trimIndent()
}

private const val MAKER = "maker@openbank.test"
private const val CHECKER = "checker@openbank.test"
