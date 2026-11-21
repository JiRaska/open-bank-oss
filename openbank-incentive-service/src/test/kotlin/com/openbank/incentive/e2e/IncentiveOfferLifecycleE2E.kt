// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.incentive.e2e

import com.openbank.incentive.integration.TestJsonWebToken
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestIdentityAssociation
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.quarkus.test.security.oidc.OidcTestSecurityIdentityAugmentor
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasKey
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * The incentive offer lifecycle over the real HTTP surface: a maker drafts a code offer, imports
 * inventory, submits it for approval, and an independent checker publishes it; the published
 * offer then binds each code to exactly one reservation under an idempotency key, settles
 * reservations by commit and release, and refuses both a reused code and a party above its
 * per-party limit — while an unpublished offer stays invisible and a viewer cannot operate the
 * API at all.
 *
 * The module's tests prove one property each; this proves the route sequence serves the whole
 * "offer is approved, inventory is claimed, reward is settled" story, so a wiring regression
 * that deregisters a route (#3371), drops the maker/checker binding at publish, or lets one code
 * or party exceed its limit fails here even when every property-level assertion stays green.
 */
@QuarkusTest
@QuarkusTestResource(
    PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_incentive_e2e")],
)
@TestMethodOrder(OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestSecurity(user = MAKER, roles = ["ROLE_OPERATOR", "ROLE_API"])
class IncentiveOfferLifecycleE2E {

    @Inject
    lateinit var testIdentity: TestIdentityAssociation

    private lateinit var offerId: String
    private lateinit var firstReservationId: String
    private lateinit var secondReservationId: String

    @BeforeEach
    fun resetToMaker() = useStaffIdentity(MAKER)

    @Test
    @Order(1)
    fun `1 - a maker drafts an offer that stays invisible until published`() {
        val created = Given {
            contentType("application/json")
            body(offerBody("e2e-offer-${UUID.randomUUID()}"))
        } When {
            post("/api/v1/incentives/offers")
        } Then {
            statusCode(201)
            body("status", equalTo("DRAFT"))
            body("maker", equalTo(MAKER))
        } Extract { path<String>("ref.id") }
        offerId = created

        When { get("/api/v1/incentives/offers") } Then {
            statusCode(200)
            body("items.ref.id", not(hasItem(offerId)))
        }
    }

    @Test
    @Order(2)
    fun `2 - the maker imports codes submits and cannot self-publish`() {
        Given {
            contentType("application/json")
            body(
                """{"codes":["E2E-0001","E2E-0002","E2E-0003","E2E-0004","E2E-0005"]}""",
            )
        } When {
            post("/api/v1/incentives/offers/$offerId/codes")
        } Then {
            statusCode(201)
            body("imported", equalTo(5))
        }

        Given { contentType("application/json") }
            .When { post("/api/v1/incentives/offers/$offerId/submit") }
            .Then {
                statusCode(200)
                body("status", equalTo("PENDING_APPROVAL"))
            }

        Given { contentType("application/json") }
            .When { post("/api/v1/incentives/offers/$offerId/publish") }
            .Then { statusCode(409) }
    }

    @Test
    @Order(3)
    fun `3 - an independent checker publishes and the offer becomes visible`() {
        useStaffIdentity(CHECKER)
        Given { contentType("application/json") }
            .When { post("/api/v1/incentives/offers/$offerId/publish") }
            .Then {
                statusCode(200)
                body("status", equalTo("PUBLISHED"))
                body("checker", equalTo(CHECKER))
            }

        When { get("/api/v1/incentives/offers") } Then {
            statusCode(200)
            body("items.ref.id", hasItem(offerId))
        }
    }

    @Test
    @Order(4)
    fun `4 - a reservation binds a code once and replays under its idempotency key`() {
        val reserved = Given {
            contentType("application/json")
            header("Idempotency-Key", "e2e-reserve-1-$offerId")
            body(reservationBody("E2E-0001", "party-1"))
        } When {
            post("/api/v1/incentives/offers/$offerId/reservations")
        } Then {
            statusCode(201)
            body("$", not(hasKey("codeDigest")))
        } Extract { path<String>("id") }
        firstReservationId = reserved

        val replayed = Given {
            contentType("application/json")
            header("Idempotency-Key", "e2e-reserve-1-$offerId")
            body(reservationBody("E2E-0001", "party-1"))
        } When {
            post("/api/v1/incentives/offers/$offerId/reservations")
        } Then {
            statusCode(201)
        } Extract { path<String>("id") }
        org.assertj.core.api.Assertions.assertThat(replayed).isEqualTo(firstReservationId)

        Given {
            contentType("application/json")
            header("Idempotency-Key", "e2e-reserve-2-$offerId")
            body(reservationBody("E2E-0001", "party-9"))
        } When {
            post("/api/v1/incentives/offers/$offerId/reservations")
        } Then { statusCode(409) }

        secondReservationId = Given {
            contentType("application/json")
            header("Idempotency-Key", "e2e-reserve-3-$offerId")
            body(reservationBody("E2E-0002", "party-1"))
        } When {
            post("/api/v1/incentives/offers/$offerId/reservations")
        } Then {
            statusCode(201)
        } Extract { path<String>("id") }
    }

    @Test
    @Order(5)
    fun `5 - committing and releasing settle the reservations and free the released code`() {
        Given { contentType("application/json") }
            .When { post("/api/v1/incentives/reservations/$firstReservationId/commit") }
            .Then { statusCode(200) }

        Given { contentType("application/json") }
            .When { post("/api/v1/incentives/reservations/$secondReservationId/release") }
            .Then { statusCode(200) }

        // The released code is inventory again: the same code reserves cleanly under a new key.
        Given {
            contentType("application/json")
            header("Idempotency-Key", "e2e-reuse-$offerId")
            body(reservationBody("E2E-0002", "party-8"))
        } When {
            post("/api/v1/incentives/offers/$offerId/reservations")
        } Then {
            statusCode(201)
        }
    }

    @Test
    @Order(6)
    fun `6 - the per-party limit refuses a third reservation for the same party`() {
        listOf("E2E-0003", "E2E-0004").forEach { code ->
            Given {
                contentType("application/json")
                header("Idempotency-Key", "e2e-$code-$offerId")
                body(reservationBody(code, "party-2"))
            } When {
                post("/api/v1/incentives/offers/$offerId/reservations")
            } Then {
                statusCode(201)
            }
        }

        Given {
            contentType("application/json")
            header("Idempotency-Key", "e2e-limit-$offerId")
            body(reservationBody("E2E-0005", "party-2"))
        } When {
            post("/api/v1/incentives/offers/$offerId/reservations")
        } Then {
            statusCode(409)
        }
    }

    @Test
    @Order(7)
    @TestSecurity(user = "viewer@openbank.test", roles = ["ROLE_VIEWER"])
    fun `7 - a viewer cannot operate the offers API`() {
        useIdentity("viewer@openbank.test", "ROLE_VIEWER")
        Given {
            contentType("application/json")
            body(offerBody("e2e-viewer-${UUID.randomUUID()}"))
        } When {
            post("/api/v1/incentives/offers")
        } Then {
            statusCode(403)
        }
    }

    private fun offerBody(name: String): String {
        val effectiveFrom = Instant.now().minusSeconds(60)
        val expiresAt = Instant.now().plusSeconds(86_400)
        return """
            {"name":"$name","version":1,"productScope":["current-account"],
             "effectiveFrom":"$effectiveFrom","expiresAt":"$expiresAt",
             "totalLimit":10,"perPartyLimit":2,"stackingPolicy":"EXCLUSIVE"}
        """.trimIndent()
    }

    private fun reservationBody(code: String, partyRef: String) = """
        {"code":"$code","partyRef":"$partyRef","productRef":"current-account"}
    """.trimIndent()

    private fun useStaffIdentity(username: String) = useIdentity(username, "ROLE_OPERATOR")

    private fun useIdentity(username: String, role: String) {
        val base = QuarkusSecurityIdentity.builder().setPrincipal { username }
            .addRole(role).build()
        val annotation = OidcSecurity(
            claims = arrayOf(
                Claim(key = "azp", value = "openbank-admin-ui"),
                Claim(key = "sub", value = username),
                Claim(key = "preferred_username", value = username),
            ),
        )
        val identity = OidcTestSecurityIdentityAugmentor(Optional.empty())
            .augment(base, arrayOf(annotation))
        TestJsonWebToken.actor = username
        testIdentity.setTestIdentity(identity)
    }
}

private const val MAKER = "maker@openbank.test"
private const val CHECKER = "checker@openbank.test"
